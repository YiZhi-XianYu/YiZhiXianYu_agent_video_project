package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.ProxyQuality;
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

    public WorkflowController(WorkflowExecutionService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/projects/{projectId}/video-proxy-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted startVideoProxyPipeline(
        @PathVariable String projectId,
        @Valid @RequestBody StartVideoProxyRequest request
    ) {
        var run = workflowService.createVideoProxyRun(projectId, request.assetId(), request.quality());
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    @PostMapping("/projects/{projectId}/multi-asset-analysis-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted startMultiAssetAnalysis(
        @PathVariable String projectId,
        @Valid @RequestBody StartMultiAssetAnalysisRequest request
    ) {
        var run = workflowService.createMultiAssetAnalysisRun(projectId, request.assetIds(), request.quality());
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    @GetMapping("/workflow-runs/{workflowRunId}")
    public WorkflowExecutionService.WorkflowSnapshot getRun(@PathVariable String workflowRunId) {
        return workflowService.getSnapshot(workflowRunId);
    }

    @GetMapping("/projects/{projectId}/workflow-runs")
    public List<WorkflowExecutionService.WorkflowHistoryItem> listProjectRuns(@PathVariable String projectId) {
        return workflowService.listProjectRuns(projectId);
    }

    public record StartVideoProxyRequest(@NotBlank String assetId, @NotNull ProxyQuality quality) {
    }

    public record StartMultiAssetAnalysisRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> assetIds,
        @NotNull ProxyQuality quality
    ) {}

    public record RunAccepted(String workflowRunId, String status, String statusUrl) {
    }
}
