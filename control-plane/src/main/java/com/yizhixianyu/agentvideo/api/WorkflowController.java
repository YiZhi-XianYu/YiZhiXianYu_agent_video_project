package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import com.yizhixianyu.agentvideo.project.ProjectService;
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

    public WorkflowController(
        WorkflowExecutionService workflowService,
        ProjectService projectService,
        AuthService authService
    ) {
        this.workflowService = workflowService;
        this.projectService = projectService;
        this.authService = authService;
    }

    @PostMapping("/projects/{projectId}/video-proxy-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted startVideoProxyPipeline(
        @PathVariable String projectId,
        @Valid @RequestBody StartVideoProxyRequest request,
        HttpServletRequest servletRequest
    ) {
        requireProject(projectId, servletRequest);
        var run = workflowService.createVideoProxyRun(projectId, request.assetId(), request.quality());
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
        var run = workflowService.createMultiAssetAnalysisRun(
            projectId, request.assetIds(), request.quality(), request.durationPrompt(), request.autoMode()
        );
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    /** 从 PAUSED 状态恢复 Workflow，继续执行下游 Task */
    @PostMapping("/workflow-runs/{workflowRunId}/continue")
    public WorkflowExecutionService.WorkflowSnapshot continueRun(
        @PathVariable String workflowRunId, HttpServletRequest request
    ) {
        requireWorkflow(workflowRunId, request);
        workflowService.continueWorkflow(workflowRunId);
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

    public record RunAccepted(String workflowRunId, String status, String statusUrl) {
    }
}
