package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @PostMapping("/projects/{projectId}/video-probe-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted startVideoProbe(
        @PathVariable String projectId,
        @Valid @RequestBody StartVideoProbeRequest request
    ) {
        var run = workflowService.createVideoProbeRun(projectId, request.assetId());
        return new RunAccepted(run.getId(), run.getStatus().name(), "/api/v1/workflow-runs/" + run.getId());
    }

    @GetMapping("/workflow-runs/{workflowRunId}")
    public WorkflowExecutionService.WorkflowSnapshot getRun(@PathVariable String workflowRunId) {
        return workflowService.getSnapshot(workflowRunId);
    }

    public record StartVideoProbeRequest(@NotBlank String assetId) {
    }

    public record RunAccepted(String workflowRunId, String status, String statusUrl) {
    }
}

