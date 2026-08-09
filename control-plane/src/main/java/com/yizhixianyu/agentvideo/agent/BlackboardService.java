package com.yizhixianyu.agentvideo.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.artifact.ArtifactEntity;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.cache.DraftConflictException;
import com.yizhixianyu.agentvideo.cache.RedisDraftService;
import com.yizhixianyu.agentvideo.cache.StoredDraft;
import com.yizhixianyu.agentvideo.execution.TaskRunEntity;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowRunEntity;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventEntity;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventRepository;
import com.yizhixianyu.agentvideo.project.ProjectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Controlled planner context. MySQL remains authoritative; Redis is a rebuildable snapshot. */
@Service
public class BlackboardService {
    private final AgentSessionRepository sessions;
    private final AgentSessionTurnRepository turns;
    private final WorkflowRunRepository workflows;
    private final TaskRunRepository tasks;
    private final ArtifactRepository artifacts;
    private final AgentTraceEventRepository traces;
    private final ProjectService projects;
    private final ObjectMapper mapper;
    private final RedisDraftService redis;
    private final Duration ttl;

    public BlackboardService(AgentSessionRepository sessions, AgentSessionTurnRepository turns,
                             WorkflowRunRepository workflows, TaskRunRepository tasks,
                             ArtifactRepository artifacts, AgentTraceEventRepository traces,
                             ProjectService projects, ObjectMapper mapper,
                             ObjectProvider<RedisDraftService> redisProvider,
                             @Value("${app.redis.agent-blackboard-ttl-seconds:1800}") long ttlSeconds) {
        this.sessions = sessions; this.turns = turns; this.workflows = workflows; this.tasks = tasks;
        this.artifacts = artifacts; this.traces = traces; this.projects = projects; this.mapper = mapper;
        this.redis = redisProvider.getIfAvailable();
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    @Transactional(readOnly = true)
    public BlackboardView get(String userId, String sessionId) {
        var session = requireOwned(userId, sessionId);
        var key = key(sessionId);
        if (redis != null) {
            try {
                var cached = redis.get(key);
                if (cached != null) return fromJson(cached.json());
            } catch (RuntimeException ignored) { }
        }
        var view = rebuild(session);
        saveSnapshot(key, view, null);
        return view;
    }

    @Transactional(readOnly = true)
    public BlackboardView refresh(String userId, String sessionId, Long expectedRevision) {
        var session = requireOwned(userId, sessionId);
        var view = rebuild(session);
        if (redis != null) saveSnapshot(key(sessionId), view, expectedRevision);
        return view;
    }

    @Transactional(readOnly = true)
    public AgentSessionEntity requireOwned(String userId, String sessionId) {
        var session = sessions.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Agent Session not found"));
        projects.getRequiredForUser(session.getProjectId(), userId);
        return session;
    }

    private BlackboardView rebuild(AgentSessionEntity session) {
        var workflow = session.getCurrentWorkflowRunId() == null ? null : workflows.findById(session.getCurrentWorkflowRunId()).orElse(null);
        var turnViews = turns.findBySessionIdOrderBySequenceNumberAsc(session.getId()).stream()
            .map(t -> new TurnView(t.getId(), t.getSequenceNumber(), t.getRole(), t.getContent(), t.getPlanId(), t.getWorkflowRunId())).toList();
        var taskViews = workflow == null ? List.<TaskView>of() : tasks.findByWorkflowRunIdOrderByCreatedAtAsc(workflow.getId()).stream()
            .map(t -> new TaskView(t.getId(), t.getNodeKey(), t.getToolName(), t.getToolVersion(), t.getStatus().name(), t.getAttempt(), t.getProgress(), t.getErrorMessage())).toList();
        var artifactViews = workflow == null ? List.<ArtifactView>of() : artifacts.findByProducerTaskRunIdIn(taskViews.stream().map(TaskView::taskRunId).toList()).stream()
            .map(a -> new ArtifactView(a.getId(), a.getExternalArtifactId(), a.getType(), a.getContentHash(), a.getProducerTaskRunId())).toList();
        var traceViews = traces.findBySessionIdOrderByOccurredAtAsc(session.getId()).stream()
            .map(t -> new TraceView(t.getEventType(), t.getTraceId(), t.getWorkflowRunId(), t.getTaskRunId(), t.getExecutionId(), t.getOccurredAt())).toList();
        var runtime = workflow == null ? new RuntimeView(null, null, 0, null, null, null) :
            new RuntimeView(workflow.getStatus().name(), workflow.getCurrentGateKey(), workflow.getProgress(),
                workflow.getErrorMessage(), nextAction(workflow), workflow.getCompletedAt());
        return new BlackboardView(1L, session.getId(), session.getUserId(), session.getProjectId(), session.getNaturalLanguageGoal(),
            session.getTargetDurationMs(), session.getStatus(), session.getCurrentPlanId(), session.getDagVersion(),
            session.getCurrentWorkflowRunId(), session.getCurrentGateKey(), runtime, turnViews, taskViews, artifactViews, traceViews);
    }

    private String nextAction(WorkflowRunEntity workflow) {
        return switch (workflow.getStatus()) {
            case PAUSED -> "请处理当前 Gate: " + workflow.getCurrentGateKey();
            case SUCCEEDED -> "成片已完成，可查看输出 Artifact";
            case FAILED -> "Workflow 失败，请检查错误并选择重试或修改方案";
            case RUNNING -> "等待 Worker 执行下一个 Task";
            default -> "等待 Workflow 启动";
        };
    }

    private void saveSnapshot(String key, BlackboardView view, Long expectedRevision) {
        if (redis == null) return;
        try {
            redis.save(key, mapper.writeValueAsString(view), ttl, expectedRevision);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Failed to serialize Blackboard snapshot", exc);
        }
    }

    private BlackboardView fromJson(String json) {
        try { return mapper.readValue(json, BlackboardView.class); }
        catch (JsonProcessingException exc) { throw new IllegalStateException("Blackboard snapshot is invalid", exc); }
    }

    private String key(String sessionId) { return "avp:v1:agent:blackboard:" + sessionId; }

    public record BlackboardView(Long revision, String sessionId, String userId, String projectId, String goal,
                                 Integer targetDurationMs, String status, String planId, Integer dagVersion,
                                 String workflowRunId, String currentGateKey, RuntimeView runtime, List<TurnView> turns,
                                 List<TaskView> tasks, List<ArtifactView> artifacts, List<TraceView> traces) {}
    public record RuntimeView(String workflowStatus, String currentGateKey, int progress, String errorMessage,
                              String nextAction, java.time.Instant completedAt) {}
    public record TurnView(String id, int sequenceNumber, String role, String content, String planId, String workflowRunId) {}
    public record TaskView(String taskRunId, String nodeKey, String toolName, String toolVersion, String status, int attempt, int progress, String errorMessage) {}
    public record ArtifactView(String id, String externalArtifactId, String type, String contentHash, String producerTaskRunId) {}
    public record TraceView(String eventType, String traceId, String workflowRunId, String taskRunId, String executionId, java.time.Instant occurredAt) {}
}
