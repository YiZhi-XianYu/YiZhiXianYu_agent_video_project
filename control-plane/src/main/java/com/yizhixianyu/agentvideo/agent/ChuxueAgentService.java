package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import com.yizhixianyu.agentvideo.workflow.DynamicWorkflowPlanner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chuxue's controlled orchestration boundary. It translates a user turn into
 * a validated Workflow preview, but never executes tools or accepts a model-
 * generated DAG directly.
 */
@Service
public class ChuxueAgentService {
    public static final String AGENT_NAME = "chuxue";

    private final AgentSessionService sessions;
    private final BlackboardService blackboard;
    private final DynamicWorkflowPlanner planner;
    private final AgentTraceService trace;

    public ChuxueAgentService(AgentSessionService sessions, BlackboardService blackboard,
                              DynamicWorkflowPlanner planner, AgentTraceService trace) {
        this.sessions = sessions;
        this.blackboard = blackboard;
        this.planner = planner;
        this.trace = trace;
    }

    @Transactional
    public Decision plan(String userId, String sessionId, String goal, Integer targetDurationMs,
                         ProxyQuality quality, List<String> assetIds, boolean autoMode) {
        var session = sessions.requireOwned(userId, sessionId);
        var turn = sessions.addTurn(userId, sessionId, "USER", goal);
        session.updateGoal(goal, targetDurationMs, turn.getId());
        var board = blackboard.refresh(userId, sessionId, null);
        var effectiveGoal = goal == null || goal.isBlank() ? board.goal() : goal.trim();
        var effectiveDuration = targetDurationMs != null ? targetDurationMs : board.targetDurationMs();
        var preview = planner.previewWithBlackboard(userId, sessionId, quality, durationPrompt(effectiveDuration),
            autoMode, null, false, effectiveGoal, assetIds);
        var traceId = UUID.randomUUID().toString();
        trace.record("CHUXUE_PLAN_PROPOSED", traceId, sessionId, turn.getId(), null, null, null, null,
            null, AGENT_NAME, null, preview.requiresConfirmation() ? "WAITING_CONFIRMATION" : "PLAN_READY",
            Map.of("targetDurationMs", Integer.parseInt(preview.intent().targetDuration()), "llmUsed", preview.llmUsed(),
                "requiresConfirmation", preview.requiresConfirmation()));
        return new Decision(sessionId, turn.getId(), traceId, effectiveGoal, preview);
    }

    private String durationPrompt(Integer durationMs) {
        return durationMs == null ? null : String.valueOf(durationMs / 1000.0) + " seconds";
    }

    public record Decision(String sessionId, String turnId, String traceId, String goal,
                           DynamicWorkflowPlanner.WorkflowPlanPreview preview) {
        public int targetDurationMs() {
            return Integer.parseInt(preview.intent().targetDuration());
        }
    }
}
