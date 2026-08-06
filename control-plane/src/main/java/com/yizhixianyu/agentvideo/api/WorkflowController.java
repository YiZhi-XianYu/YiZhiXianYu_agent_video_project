package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.WorkflowAdvanceCoordinator;
import com.yizhixianyu.agentvideo.execution.WorkflowAdmissionCoordinator;
import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.workflow.DynamicWorkflowPlanner;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import com.yizhixianyu.agentvideo.agent.AgentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {

    private final WorkflowExecutionService workflowService;
    private final ProjectService projectService;
    private final AuthService authService;
    private final DynamicWorkflowPlanner dynamicPlanner;
    private final WorkflowAdvanceCoordinator advanceCoordinator;
    private final WorkflowAdmissionCoordinator admissionCoordinator;
    private final AgentSessionService agentSessions;

    public WorkflowController(
        WorkflowExecutionService workflowService,
        ProjectService projectService,
        AuthService authService,
        DynamicWorkflowPlanner dynamicPlanner,
        WorkflowAdvanceCoordinator advanceCoordinator,
        WorkflowAdmissionCoordinator admissionCoordinator,
        AgentSessionService agentSessions
    ) {
        this.workflowService = workflowService;
        this.projectService = projectService;
        this.authService = authService;
        this.dynamicPlanner = dynamicPlanner;
        this.advanceCoordinator = advanceCoordinator;
        this.admissionCoordinator = admissionCoordinator;
        this.agentSessions = agentSessions;
    }

    @PostMapping("/projects/{projectId}/video-proxy-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted startVideoProxyPipeline(
        @PathVariable String projectId,
        @Valid @RequestBody StartVideoProxyRequest request,
        HttpServletRequest servletRequest
    ) {
        requireProject(projectId, servletRequest);
        var run = admissionCoordinator.createVideoProxyRun(projectId, request.assetId(), request.quality());
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    /** 启动多素材分析 Workflow。支持 autoMode 全自动模式开关 */
    @PostMapping("/projects/{projectId}/multi-asset-analysis-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted startMultiAssetAnalysis(
        @PathVariable String projectId,
        @Valid @RequestBody StartMultiAssetAnalysisRequest request,
        HttpServletRequest servletRequest
    ) {
        requireProject(projectId, servletRequest);
        var run = admissionCoordinator.createMultiAssetAnalysisRun(projectId, request.assetIds(), request.quality(), request.durationPrompt(), request.autoMode());
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    /** 生成执行前候选 DAG。只返回后端受控的节点、边和中文解释，不创建 Task。 */
    @PostMapping("/projects/{projectId}/workflow-plans/preview")
    public DynamicWorkflowPlanner.WorkflowPlanPreview previewWorkflowPlan(
        @PathVariable String projectId,
        @Valid @RequestBody PreviewWorkflowPlanRequest request,
        HttpServletRequest servletRequest
    ) {
        requireProject(projectId, servletRequest);
        return dynamicPlanner.preview(
            request.quality(), request.durationPrompt(), request.autoMode(),
            request.plannerCapabilities(), request.useDefault(), request.goal(), request.assetIds()
        );
    }

    /** 确认候选 DAG 并创建真实 Workflow。前端只能提交后端返回的结构化 Definition。 */
    @PostMapping("/projects/{projectId}/workflow-plans/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted confirmWorkflowPlan(
        @PathVariable String projectId,
        @Valid @RequestBody ConfirmWorkflowPlanRequest request,
        HttpServletRequest servletRequest
    ) {
        requireProject(projectId, servletRequest);
        var preview = dynamicPlanner.preview(
            request.quality(), request.durationPrompt(), request.autoMode(),
            request.plannerCapabilities(), request.useDefault(), request.goal(), request.assetIds()
        );
        var definition = dynamicPlanner.applyCanvasEdits(preview.definition(), request.removedNodeIds(), request.removedEdgeIds(), request.addedEdges());
        var user = authService.requireUser(servletRequest);
        var session = request.sessionId() == null ? null : agentSessions.requireOwned(user.id(), request.sessionId());
        var planId = session == null ? null : "plan-" + java.util.UUID.randomUUID();
        var traceId = session == null ? null : java.util.UUID.randomUUID().toString();
        var run = admissionCoordinator.createMultiAssetAnalysisRun(projectId, request.assetIds(), request.quality(), request.durationPrompt(), request.autoMode(), definition,
            session == null ? null : new WorkflowExecutionService.AgentContext(session.getId(), request.turnId(), planId, traceId));
        if (session != null) {
            agentSessions.attachWorkflow(user.id(), session.getId(), request.turnId(), planId, run.getId(), definition.definitionVersion());
        }
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    @PostMapping("/projects/{projectId}/workflow-plans/validate")
    public DynamicWorkflowPlanner.ValidationResult validateWorkflowPlan(
        @PathVariable String projectId,
        @Valid @RequestBody ConfirmWorkflowPlanRequest request,
        HttpServletRequest servletRequest
    ) {
        requireProject(projectId, servletRequest);
        var preview = dynamicPlanner.preview(request.quality(), request.durationPrompt(), request.autoMode(), request.plannerCapabilities(), request.useDefault(), request.goal(), request.assetIds());
        return dynamicPlanner.validateCanvasEdits(preview.definition(), request.removedNodeIds(), request.removedEdgeIds(), request.addedEdges());
    }

    /** 从 PAUSED 状态恢复 Workflow，继续执行下游 Task */
    @PostMapping("/workflow-runs/{workflowRunId}/continue")
    public WorkflowExecutionService.WorkflowSnapshot continueRun(
        @PathVariable String workflowRunId, HttpServletRequest request
    ) {
        requireWorkflow(workflowRunId, request);
        advanceCoordinator.continueWorkflow(workflowRunId);
        return workflowService.getSnapshot(workflowRunId);
    }

    @GetMapping("/workflow-runs/{workflowRunId}")
    public WorkflowExecutionService.WorkflowSnapshot getRun(
        @PathVariable String workflowRunId, HttpServletRequest request
    ) {
        return requireWorkflow(workflowRunId, request);
    }

    @GetMapping("/projects/{projectId}/workflow-runs")
    public List<WorkflowExecutionService.WorkflowHistoryItem> listProjectRuns(
        @PathVariable String projectId, HttpServletRequest request
    ) {
        requireProject(projectId, request);
        return workflowService.listProjectRuns(projectId);
    }

    private void requireProject(String projectId, HttpServletRequest request) {
        projectService.getRequiredForUser(projectId, authService.requireUser(request).id());
    }

    private WorkflowExecutionService.WorkflowSnapshot requireWorkflow(
        String workflowRunId, HttpServletRequest request
    ) {
        var snapshot = workflowService.getSnapshot(workflowRunId);
        requireProject(snapshot.projectId(), request);
        return snapshot;
    }

    public record StartVideoProxyRequest(@NotBlank String assetId, @NotNull ProxyQuality quality) {
    }

    /** 多素材分析请求。autoMode=true 时跳过所有审核 Gate。 */
    public record StartMultiAssetAnalysisRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> assetIds,
        @NotNull ProxyQuality quality,
        String durationPrompt,
        boolean autoMode
    ) {}

    public record WorkflowCapabilitiesRequest(
        boolean vlmAnalysis,
        boolean sourceTranscription,
        boolean subtitles,
        boolean bgm
    ) {
        public DynamicWorkflowPlanner.WorkflowCapabilities toCapabilities() {
            return new DynamicWorkflowPlanner.WorkflowCapabilities(
                vlmAnalysis, sourceTranscription, subtitles, bgm
            );
        }
    }

    public record PreviewWorkflowPlanRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> assetIds,
        @NotNull ProxyQuality quality,
        String durationPrompt,
        String goal,
        String sessionId,
        String turnId,
        boolean autoMode,
        WorkflowCapabilitiesRequest capabilities,
        boolean useDefault,
        List<String> removedNodeIds,
        List<String> removedEdgeIds,
        List<DynamicWorkflowPlanner.CanvasEdge> addedEdges
    ) {
        public DynamicWorkflowPlanner.WorkflowCapabilities plannerCapabilities() {
            return capabilities == null ? null : capabilities.toCapabilities();
        }
    }

    public record ConfirmWorkflowPlanRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> assetIds,
        @NotNull ProxyQuality quality,
        String durationPrompt,
        String goal,
        String sessionId,
        String turnId,
        boolean autoMode,
        WorkflowCapabilitiesRequest capabilities,
        boolean useDefault,
        List<String> removedNodeIds,
        List<String> removedEdgeIds,
        List<DynamicWorkflowPlanner.CanvasEdge> addedEdges
    ) {
        public DynamicWorkflowPlanner.WorkflowCapabilities plannerCapabilities() {
            return capabilities == null ? null : capabilities.toCapabilities();
        }
    }

    public record RunAccepted(String workflowRunId, String status, String statusUrl) {
    }
}
