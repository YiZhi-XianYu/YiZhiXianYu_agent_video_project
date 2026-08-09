package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.auth.AccessDeniedException;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import com.yizhixianyu.agentvideo.cache.RedisDraftService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class AgentSessionService {
    private final AgentSessionRepository sessions;
    private final AgentSessionTurnRepository turns;
    private final ProjectService projects;
    private final AgentTraceService trace;
    private final RedisDraftService redis;

    public AgentSessionService(AgentSessionRepository sessions, AgentSessionTurnRepository turns,
                               ProjectService projects, AgentTraceService trace,
                               ObjectProvider<RedisDraftService> redisProvider) {
        this.sessions = sessions; this.turns = turns; this.projects = projects; this.trace = trace;
        this.redis = redisProvider.getIfAvailable();
    }

    @Transactional
    public AgentSessionEntity create(String userId, String projectId, String goal, Integer targetDurationMs) {
        projects.getRequiredForUser(projectId, userId);
        var session = sessions.save(new AgentSessionEntity(userId, projectId, goal, targetDurationMs));
        trace.record("SESSION_CREATED", UUID.randomUUID().toString(), session.getId(), null, null, null, null,
            null, null, "agent-runtime", null, session.getStatus(), java.util.Map.of("projectId", projectId));
        return session;
    }

    @Transactional(readOnly = true)
    public List<AgentSessionEntity> list(String userId, String projectId) {
        projects.getRequiredForUser(projectId, userId);
        return sessions.findByProjectIdAndUserIdOrderByUpdatedAtDesc(projectId, userId);
    }

    @Transactional
    public AgentSessionTurnEntity addTurn(String userId, String sessionId, String role, String content) {
        var session = requireOwned(userId, sessionId);
        var turn = turns.save(new AgentSessionTurnEntity(sessionId, (int) turns.countBySessionId(sessionId) + 1, role, content));
        session.updateGoal("USER".equalsIgnoreCase(role) ? content : null, null, turn.getId());
        trace.record("SESSION_TURN_ADDED", UUID.randomUUID().toString(), sessionId, turn.getId(), session.getCurrentPlanId(),
            session.getCurrentWorkflowRunId(), null, null, null, "agent-runtime", null, role, java.util.Map.of());
        return turn;
    }

    @Transactional
    public AgentSessionTurnEntity addPlanningTurn(String userId, String sessionId, String content) {
        requireOwned(userId, sessionId);
        var existing = turns.findBySessionIdOrderBySequenceNumberAsc(sessionId);
        if (existing.size() >= 2) {
            var assistant = existing.get(existing.size() - 1);
            var user = existing.get(existing.size() - 2);
            if ("ASSISTANT".equalsIgnoreCase(assistant.getRole())
                && "USER".equalsIgnoreCase(user.getRole())
                && content.equals(user.getContent())) return user;
        }
        return addTurn(userId, sessionId, "USER", content);
    }

    @Transactional
    public void attachWorkflow(String userId, String sessionId, String turnId, String planId,
                               String workflowRunId, int dagVersion) {
        var session = requireOwned(userId, sessionId);
        session.attachWorkflow(workflowRunId, turnId, planId, dagVersion);
        if (turnId != null) {
            turns.findById(turnId).ifPresent(turn -> {
                turn.linkPlan(planId);
                turn.linkWorkflow(workflowRunId);
            });
        }
        trace.record("SESSION_WORKFLOW_ATTACHED", null, sessionId, turnId, planId, workflowRunId, null,
            null, null, "agent-runtime", null, session.getStatus(), java.util.Map.of("dagVersion", dagVersion));
    }

    @Transactional
    public void recordPlan(String userId, String sessionId, String turnId, String planId, int dagVersion) {
        var session = requireOwned(userId, sessionId);
        session.recordPlan(turnId, planId, dagVersion);
        if (turnId != null) turns.findById(turnId).ifPresent(turn -> turn.linkPlan(planId));
        trace.record("SESSION_PLAN_CREATED", null, sessionId, turnId, planId, null, null, null, null,
            "planner", null, session.getStatus(), java.util.Map.of("dagVersion", dagVersion));
    }

    @Transactional
    public AgentSessionEntity syncRuntime(String userId, String sessionId, String workflowStatus, String gateKey) {
        var session = requireOwned(userId, sessionId);
        session.syncRuntime(workflowStatus, gateKey);
        return session;
    }

    /** Synchronizes a Workflow-owned runtime transition without requiring an HTTP user context. */
    @Transactional
    public void syncRuntimeFromWorkflow(String sessionId, String workflowStatus, String gateKey) {
        if (sessionId == null || sessionId.isBlank()) return;
        sessions.findById(sessionId).ifPresent(session -> {
            session.syncRuntime(workflowStatus, gateKey);
            if (redis != null) {
                try { redis.delete("avp:v1:agent:blackboard:" + sessionId); }
                catch (RuntimeException ignored) { /* Redis is a rebuildable snapshot. */ }
            }
        });
    }

    @Transactional(readOnly = true)
    public AgentSessionEntity requireOwned(String userId, String sessionId) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Agent Session not found"));
        if (!session.getUserId().equals(userId)) throw new AccessDeniedException("Agent Session does not belong to user");
        return session;
    }

    @Transactional(readOnly = true)
    public List<AgentSessionTurnEntity> turns(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        return turns.findBySessionIdOrderBySequenceNumberAsc(sessionId);
    }
}
