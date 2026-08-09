package com.yizhixianyu.agentvideo.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import com.yizhixianyu.agentvideo.execution.RunStatus;
import com.yizhixianyu.agentvideo.execution.WorkflowAdmissionCoordinator;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.WorkflowRunEntity;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import com.yizhixianyu.agentvideo.workflow.DynamicWorkflowPlanner;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinitionValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final AgentPlanSnapshotRepository snapshots;
    private final ObjectMapper mapper;
    private final WorkflowAdmissionCoordinator admission;
    private final WorkflowDefinitionValidator validator;
    private final ChuxueIntentParser intentParser;
    private final ToolServiceClient toolClient;
    private final WorkflowRunRepository workflows;

    public ChuxueAgentService(AgentSessionService sessions, BlackboardService blackboard,
                              DynamicWorkflowPlanner planner, AgentTraceService trace,
                              AgentPlanSnapshotRepository snapshots, ObjectMapper mapper,
                              WorkflowAdmissionCoordinator admission, WorkflowDefinitionValidator validator,
                              ChuxueIntentParser intentParser, ToolServiceClient toolClient,
                              WorkflowRunRepository workflows) {
        this.sessions = sessions;
        this.blackboard = blackboard;
        this.planner = planner;
        this.trace = trace;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.admission = admission;
        this.validator = validator;
        this.intentParser = intentParser;
        this.toolClient = toolClient;
        this.workflows = workflows;
    }

    public ChatResult chat(String userId, String sessionId, String message,
                           List<Map<String, String>> history) {
        // Commit the user turn before waiting for the model. A conversation
        // switch while the request is running must not hide the sent message.
        var userTurn = sessions.addTurn(userId, sessionId, "USER", message);
        var board = blackboard.get(userId, sessionId);
        var context = Map.<String, Object>of(
            "goal", board.goal() == null ? "" : board.goal(),
            "targetDurationMs", board.targetDurationMs() == null ? 30000 : board.targetDurationMs(),
            "workflowStatus", board.runtime() == null ? "IDLE" : String.valueOf(board.runtime().workflowStatus())
        );
        var response = toolClient.chat(new ToolServiceClient.ChuxueChatRequest(
            message, history == null ? List.of() : history, context));
        AgentSessionTurnEntity assistantTurn = null;
        if (response.llmUsed() && response.reply() != null && !response.reply().isBlank()) {
            assistantTurn = sessions.addTurn(userId, sessionId, "ASSISTANT", response.reply());
        }
        return new ChatResult(response.reply(), response.shouldPlan(), response.modelRoute(), response.llmUsed(),
            userTurn.getId(), assistantTurn == null ? null : assistantTurn.getId());
    }

    @Transactional
    public Decision plan(String userId, String sessionId, String goal, Integer targetDurationMs,
                         ProxyQuality quality, List<String> assetIds, boolean autoMode) {
        return plan(userId, sessionId, goal, targetDurationMs, quality, assetIds, autoMode, null, null);
    }

    @Transactional
    public Decision plan(String userId, String sessionId, String goal, Integer targetDurationMs,
                         ProxyQuality quality, List<String> assetIds, boolean autoMode,
                         Set<String> reviewGateKeys) {
        return plan(userId, sessionId, goal, targetDurationMs, quality, assetIds, autoMode, reviewGateKeys, null);
    }

    @Transactional
    public Decision plan(String userId, String sessionId, String goal, Integer targetDurationMs,
                         ProxyQuality quality, List<String> assetIds, boolean autoMode,
                         Set<String> reviewGateKeys, String userTurnId) {
        var session = sessions.requireOwned(userId, sessionId);
        rejectWhileWorkflowActive(session);
        var parsedIntent = intentParser.parse(goal, targetDurationMs, autoMode);
        var turn = userTurnId == null
            ? sessions.addPlanningTurn(userId, sessionId, goal)
            : sessions.requireUserTurn(userId, sessionId, userTurnId);
        session.updateGoal(goal, parsedIntent.targetDurationMs(), turn.getId());
        if (parsedIntent.needsClarification()) {
            trace.record("CHUXUE_CLARIFICATION_REQUIRED", UUID.randomUUID().toString(), sessionId, turn.getId(), null, null,
                null, null, null, AGENT_NAME, null, "WAITING_CLARIFICATION",
                Map.of("question", parsedIntent.clarificationQuestion()));
            return new Decision(null, sessionId, turn.getId(), null, goal, null, parsedIntent);
        }

        var board = blackboard.refresh(userId, sessionId, null);
        boolean modificationOnly = intentParser.isModificationOnly(goal);
        var effectiveGoal = goal == null || goal.isBlank() ? board.goal()
            : modificationOnly && board.goal() != null && !board.goal().isBlank()
                ? board.goal() + "\nUser refinement: " + goal.trim() : goal.trim();
        var effectiveDuration = targetDurationMs != null ? targetDurationMs
            : modificationOnly && !intentParser.hasDuration(goal) && board.targetDurationMs() != null
                ? board.targetDurationMs() : parsedIntent.targetDurationMs();
        var requested = (parsedIntent.subtitlesExplicit() || parsedIntent.bgmExplicit())
            ? new DynamicWorkflowPlanner.WorkflowCapabilities(
                true,
                !parsedIntent.subtitlesExplicit() || parsedIntent.subtitles(),
                !parsedIntent.subtitlesExplicit() || parsedIntent.subtitles(),
                parsedIntent.bgmExplicit() ? parsedIntent.bgm() : true)
            : null;
        var preview = planner.previewWithBlackboard(userId, sessionId, quality, durationPrompt(effectiveDuration),
            autoMode, requested, false, effectiveGoal, assetIds, reviewGateKeys);
        var traceId = UUID.randomUUID().toString();
        var definitionJson = toJson(preview.definition());
        var assetIdsJson = toJson(assetIds == null ? List.of() : assetIds);
        var snapshot = snapshots.save(new AgentPlanSnapshotEntity(sessionId, turn.getId(), session.getProjectId(),
            traceId, quality.name(), autoMode, effectiveGoal,
            Integer.parseInt(preview.intent().targetDuration()), assetIdsJson, definitionJson));
        sessions.recordPlan(userId, sessionId, turn.getId(), snapshot.getId(), preview.definition().definitionVersion());
        trace.record("CHUXUE_PLAN_PROPOSED", traceId, sessionId, turn.getId(), snapshot.getId(), null, null, null,
            null, AGENT_NAME, null, preview.requiresConfirmation() ? "WAITING_CONFIRMATION" : "PLAN_READY",
            Map.of("targetDurationMs", Integer.parseInt(preview.intent().targetDuration()),
                "llmUsed", preview.llmUsed(), "requiresConfirmation", preview.requiresConfirmation()));
        return new Decision(snapshot.getId(), sessionId, turn.getId(), traceId, effectiveGoal, preview, parsedIntent);
    }

    @Transactional
    public Confirmed confirm(String userId, String projectId, String planId) {
        var snapshot = snapshots.findLockedById(planId)
            .orElseThrow(() -> new IllegalArgumentException("初雪方案不存在: " + planId));
        var session = sessions.requireOwned(userId, snapshot.getSessionId());
        if (!session.getProjectId().equals(snapshot.getProjectId()) || !snapshot.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("方案项目不匹配");
        }

        var active = activeWorkflow(session);
        if (active != null) {
            // Confirm is idempotent for the plan that already started this Workflow.
            if (planId.equals(session.getCurrentPlanId())) {
                return confirmed(snapshot.getId(), active);
            }
            throw new ActiveSessionWorkflowException(active.getId(), active.getStatus().name());
        }
        if (!"PROPOSED".equals(snapshot.getStatus())) {
            throw new IllegalStateException("初雪方案已确认或不可重复确认");
        }

        try {
            var definition = mapper.readValue(snapshot.getDefinitionJson(), WorkflowDefinition.class);
            validator.validate(definition);
            List<String> assets = mapper.readValue(snapshot.getAssetIdsJson(),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            var run = admission.createMultiAssetAnalysisRun(snapshot.getProjectId(), assets,
                ProxyQuality.valueOf(snapshot.getQuality()), durationPrompt(snapshot.getTargetDurationMs()),
                snapshot.isAutoMode(), definition,
                new WorkflowExecutionService.AgentContext(snapshot.getSessionId(), snapshot.getTurnId(),
                    snapshot.getId(), snapshot.getTraceId()));
            snapshot.confirm();
            sessions.attachWorkflow(userId, snapshot.getSessionId(), snapshot.getTurnId(), snapshot.getId(),
                run.getId(), definition.definitionVersion());
            sessions.addTurn(userId, snapshot.getSessionId(), "SYSTEM",
                "Workflow 已启动（" + run.getId() + "），进度会持续同步到当前会话。");
            trace.record("CHUXUE_PLAN_CONFIRMED", snapshot.getTraceId(), snapshot.getSessionId(),
                snapshot.getTurnId(), snapshot.getId(), run.getId(), null, null, null,
                AGENT_NAME, null, "EXECUTING", Map.of("workflowRunId", run.getId()));
            return confirmed(snapshot.getId(), run);
        } catch (JsonProcessingException | IllegalArgumentException exc) {
            throw new IllegalStateException("初雪方案无效，无法启动 Workflow", exc);
        }
    }

    private Confirmed confirmed(String planId, WorkflowRunEntity workflow) {
        return new Confirmed(planId, workflow.getId(), workflow.getStatus().name(),
            "/api/v1/workflow-runs/" + workflow.getId());
    }

    private WorkflowRunEntity activeWorkflow(AgentSessionEntity session) {
        if (session.getCurrentWorkflowRunId() == null) return null;
        return workflows.findById(session.getCurrentWorkflowRunId())
            .filter(workflow -> workflow.getStatus() != RunStatus.SUCCEEDED && workflow.getStatus() != RunStatus.FAILED)
            .orElse(null);
    }

    private void rejectWhileWorkflowActive(AgentSessionEntity session) {
        var active = activeWorkflow(session);
        if (active != null) throw new ActiveSessionWorkflowException(active.getId(), active.getStatus().name());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("无法保存初雪方案", exc);
        }
    }

    private String durationPrompt(Integer durationMs) {
        return durationMs == null ? null : String.valueOf(durationMs / 1000.0) + " seconds";
    }

    public record Decision(String planId, String sessionId, String turnId, String traceId, String goal,
                           DynamicWorkflowPlanner.WorkflowPlanPreview preview, ChuxueIntentParser.Intent intent) {
        public int targetDurationMs() {
            return intent != null ? intent.targetDurationMs()
                : preview == null ? 0 : Integer.parseInt(preview.intent().targetDuration());
        }
    }

    public record Confirmed(String planId, String workflowRunId, String status, String statusUrl) {}

    public record ChatResult(String reply, boolean shouldPlan, Map<String, Object> modelRoute, boolean llmUsed,
                             String userTurnId, String assistantTurnId) {}
}
