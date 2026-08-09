package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.project.ProjectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** User/project workflow admission control with Redis leases and a MySQL fallback. */
@Service
public class WorkflowConcurrencyService {
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
        for i = 1, #KEYS do redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', ARGV[1]) end
        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end
        if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 0 end
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[5])
        redis.call('ZADD', KEYS[2], ARGV[2], ARGV[5])
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[6]))
        redis.call('EXPIRE', KEYS[2], tonumber(ARGV[6]))
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
        redis.call('ZREM', KEYS[1], ARGV[1])
        redis.call('ZREM', KEYS[2], ARGV[1])
        return 1
        """, Long.class);

    private final WorkflowRunRepository workflows;
    private final ProjectService projects;
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final int userLimit;
    private final int projectLimit;
    private final long leaseSeconds;

    public WorkflowConcurrencyService(
        WorkflowRunRepository workflows,
        ProjectService projects,
        ObjectProvider<StringRedisTemplate> redisProvider,
        @Value("${app.redis.enabled:false}") boolean redisEnabled,
        @Value("${app.redis.workflow-user-concurrency-limit:2}") int userLimit,
        @Value("${app.redis.workflow-project-concurrency-limit:2}") int projectLimit,
        @Value("${app.redis.workflow-concurrency-lease-seconds:7200}") long leaseSeconds
    ) {
        this.workflows = workflows;
        this.projects = projects;
        this.redis = redisProvider.getIfAvailable();
        this.redisEnabled = redisEnabled;
        this.userLimit = Math.max(1, userLimit);
        this.projectLimit = Math.max(1, projectLimit);
        this.leaseSeconds = Math.max(60, leaseSeconds);
    }

    public void acquire(String projectId, String workflowRunId) {
        var project = projects.getRequired(projectId);
        String userKey = key("user", project.getOwnerUserId());
        String projectKey = key("project", projectId);
        if (redisEnabled && redis != null) {
            try {
                // MySQL is the source of truth.  A process crash or a terminal
                // transition during container restart can leave an old Redis
                // member behind; reconcile only IDs that are verified terminal
                // before applying the new admission decision.
                reconcileTerminalLeases(project.getOwnerUserId(), projectId, userKey, projectKey);
                long now = System.currentTimeMillis();
                long expiry = now + leaseSeconds * 1000;
                Long acquired = redis.execute(ACQUIRE_SCRIPT, java.util.List.of(userKey, projectKey),
                    Long.toString(now), Long.toString(expiry), Integer.toString(userLimit),
                    Integer.toString(projectLimit), workflowRunId, Long.toString(leaseSeconds));
                if (Long.valueOf(1L).equals(acquired)) return;
                throw new WorkflowConcurrencyLimitException("user/project", Math.min(userLimit, projectLimit));
            } catch (WorkflowConcurrencyLimitException exception) { throw exception; }
            catch (RuntimeException ignored) { }
        }
        long userActive = projects.list(project.getOwnerUserId()).stream()
            .flatMap(item -> workflows.findByProjectIdOrderByCreatedAtDesc(item.getId()).stream())
            .filter(this::active).count();
        long projectActive = workflows.findByProjectIdOrderByCreatedAtDesc(projectId).stream().filter(this::active).count();
        if (userActive >= userLimit) throw new WorkflowConcurrencyLimitException("user", userLimit);
        if (projectActive >= projectLimit) throw new WorkflowConcurrencyLimitException("project", projectLimit);
    }

    private void reconcileTerminalLeases(String userId, String projectId, String userKey, String projectKey) {
        Set<String> terminalInUser = new HashSet<>();
        for (var ownedProject : projects.list(userId)) {
            for (var workflow : workflows.findByProjectIdOrderByCreatedAtDesc(ownedProject.getId())) {
                if (isTerminal(workflow)) terminalInUser.add(workflow.getId());
            }
        }
        Set<String> terminalInProject = new HashSet<>();
        for (var workflow : workflows.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (isTerminal(workflow)) terminalInProject.add(workflow.getId());
        }
        // Remove only database-verified terminal members. Never clear the
        // complete sorted set, since another active project/run may exist.
        for (var workflowId : terminalInUser) {
            redis.execute(RELEASE_SCRIPT, java.util.List.of(userKey, projectKey), workflowId);
        }
        for (var workflowId : terminalInProject) {
            if (!terminalInUser.contains(workflowId)) {
                redis.execute(RELEASE_SCRIPT, java.util.List.of(userKey, projectKey), workflowId);
            }
        }
    }

    public void release(String projectId, String workflowRunId) {
        if (!redisEnabled || redis == null) return;
        try {
            var project = projects.getRequired(projectId);
            redis.execute(RELEASE_SCRIPT, java.util.List.of(key("user", project.getOwnerUserId()), key("project", projectId)), workflowRunId);
        } catch (RuntimeException ignored) { }
    }

    private boolean active(WorkflowRunEntity workflow) {
        return workflow.getStatus() == RunStatus.CREATED || workflow.getStatus() == RunStatus.RUNNING || workflow.getStatus() == RunStatus.PAUSED;
    }

    private boolean isTerminal(WorkflowRunEntity workflow) {
        return workflow.getStatus() == RunStatus.SUCCEEDED || workflow.getStatus() == RunStatus.FAILED;
    }

    private String key(String scope, String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "avp:v1:workflow:concurrency:" + scope + ":" + java.util.HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
