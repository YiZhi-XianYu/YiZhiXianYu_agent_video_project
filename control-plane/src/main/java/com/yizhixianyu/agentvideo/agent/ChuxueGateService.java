package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.execution.WorkflowAdvanceCoordinator;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.WorkflowRunEntity;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Unified, user-facing Gate collaboration facade. */
@Service
public class ChuxueGateService {
    private final WorkflowRunRepository workflows;
    private final WorkflowExecutionService execution;
    private final WorkflowAdvanceCoordinator advance;
    private final AgentTraceService trace;
    private final TaskRunRepository tasks;
    private final ArtifactRepository artifacts;
    private final GateFeedbackRepository feedbackRepository;
    private final ObjectMapper mapper;

    public ChuxueGateService(WorkflowRunRepository workflows, WorkflowExecutionService execution,
                             WorkflowAdvanceCoordinator advance, AgentTraceService trace,
                             TaskRunRepository tasks, ArtifactRepository artifacts,
                             GateFeedbackRepository feedbackRepository, ObjectMapper mapper) {
        this.workflows = workflows; this.execution = execution; this.advance = advance; this.trace = trace;
        this.tasks = tasks; this.artifacts = artifacts;
        this.feedbackRepository = feedbackRepository; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public GateView current(String workflowRunId) {
        var workflow = workflows.findById(workflowRunId).orElseThrow();
        var key = workflow.getCurrentGateKey();
        var candidateIds = key == null ? List.<String>of() : tasks.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId).stream()
            .filter(t -> "bgm_select".equals(t.getNodeKey())).findFirst()
            .map(t -> artifacts.findByProducerTaskRunId(t.getId()).stream()
                .filter(a -> "BGM_CANDIDATE".equals(a.getType())).map(a -> a.getExternalArtifactId()).toList())
            .orElse(List.of());
        return new GateView(workflowRunId, workflow.getStatus().name(), key, label(key), description(key), options(key), candidateIds);
    }

    @Transactional
    public GateView decide(String workflowRunId, DecisionRequest request) {
        var workflow = workflows.findLockedById(workflowRunId).orElseThrow();
        if (workflow.getStatus() != com.yizhixianyu.agentvideo.execution.RunStatus.PAUSED) {
            throw new IllegalStateException("Workflow is not waiting for a Gate");
        }
        var gate = workflow.getCurrentGateKey();
        if (gate == null || request.action() == null) throw new IllegalArgumentException("Gate action is required");
        if (!options(gate).contains(request.action().name())) throw new IllegalArgumentException("Action is not supported by current Gate");
        switch (request.action()) {
            case ACCEPT, SKIP -> advance.continueWorkflow(workflowRunId);
            case CANCEL -> advance.cancelWorkflow(workflowRunId);
            case MODIFY -> applyModification(workflow, request.payload());
            case REGENERATE -> regenerate(workflow);
        }
        trace.record("GATE_DECISION", workflow.getAgentTraceId(), workflow.getAgentSessionId(), workflow.getAgentTurnId(),
            workflow.getAgentPlanId(), workflowRunId, null, null, null, "chuxue", null, request.action().name(),
            Map.of("gateKey", gate, "action", request.action().name()));
        return current(workflowRunId);
    }

    private void applyModification(WorkflowRunEntity workflow, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) throw new IllegalArgumentException("Modification payload is required");
        switch (workflow.getCurrentGateKey()) {
            case "gate_story_edit" -> advance.applyCustomStoryPlan(workflow.getId(), payload);
            case "gate_timeline_preview" -> advance.applyCustomTimeline(workflow.getId(), payload);
            case "gate_bgm_review" -> {
                var artifactId = String.valueOf(payload.getOrDefault("candidateArtifactId", ""));
                if (artifactId.isBlank()) throw new IllegalArgumentException("candidateArtifactId is required");
                advance.selectBgmCandidate(workflow.getId(), artifactId);
            }
            default -> throw new IllegalArgumentException("This Gate does not support structured modification");
        }
    }

    private void regenerate(WorkflowRunEntity workflow) {
        switch (workflow.getCurrentGateKey()) {
            case "gate_bgm_review" -> advance.refreshBgmCandidates(workflow.getId());
            default -> throw new IllegalArgumentException("This Gate does not support regeneration");
        }
    }

    @Transactional
    public FeedbackView feedback(String workflowRunId, FeedbackRequest request) {
        var workflow = workflows.findById(workflowRunId).orElseThrow();
        var gate = request.gateKey() == null || request.gateKey().isBlank() ? workflow.getCurrentGateKey() : request.gateKey();
        if (gate == null || !gate.equals(workflow.getCurrentGateKey())) throw new IllegalArgumentException("Feedback must target the current Gate");
        if (request.score() < 1 || request.score() > 5) throw new IllegalArgumentException("score must be between 1 and 5");
        try {
            var entity = feedbackRepository.save(new GateFeedbackEntity(workflowRunId, workflow.getProjectId(), gate,
                request.score(), request.action(), mapper.writeValueAsString(request.reasonCodes() == null ? List.of() : request.reasonCodes()),
                request.comment(), mapper.writeValueAsString(request.artifactIds() == null ? List.of() : request.artifactIds())));
            trace.record("GATE_FEEDBACK", workflow.getAgentTraceId(), workflow.getAgentSessionId(), workflow.getAgentTurnId(),
                workflow.getAgentPlanId(), workflowRunId, null, null, null, "chuxue", null, "RECORDED",
                Map.of("gateKey", gate, "score", request.score(), "action", request.action() == null ? "" : request.action()));
            return new FeedbackView(entity.getId(), gate, request.score(), true);
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to persist Gate feedback", exc);
        }
    }

    private String label(String key) { return key == null ? null : switch (key) {
        case "gate_shot_ranking" -> "镜头排序审核"; case "gate_story_edit" -> "故事计划编辑";
        case "gate_timeline_preview" -> "时间线预览"; case "gate_bgm_review" -> "背景音乐选择";
        case "gate_render_review" -> "最终成片预览"; default -> "工具执行确认";
    }; }
    private String description(String key) { return key == null ? null : "请根据当前创作目标决定是否接受、修改、跳过或重新生成。"; }
    private List<String> options(String key) { return key == null ? List.of() : switch (key) {
        case "gate_story_edit", "gate_timeline_preview" -> List.of("ACCEPT", "MODIFY", "CANCEL");
        case "gate_bgm_review" -> List.of("ACCEPT", "MODIFY", "REGENERATE", "SKIP", "CANCEL");
        default -> List.of("ACCEPT", "SKIP", "CANCEL");
    }; }

    public enum Action { ACCEPT, MODIFY, SKIP, REGENERATE, CANCEL }
    public record DecisionRequest(Action action, Map<String, Object> payload) {}
    public record FeedbackRequest(String gateKey, int score, List<String> reasonCodes, String comment,
                                  String action, List<String> artifactIds) {}
    public record FeedbackView(String feedbackId, String gateKey, int score, boolean recorded) {}
    public record GateView(String workflowRunId, String workflowStatus, String gateKey, String label,
                           String description, List<String> options, List<String> candidateArtifactIds) {}
}
