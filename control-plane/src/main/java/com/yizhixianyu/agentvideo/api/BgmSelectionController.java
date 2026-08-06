package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.WorkflowAdvanceCoordinator;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class BgmSelectionController {

    private final WorkflowExecutionService workflowService;
    private final WorkflowAdvanceCoordinator advanceCoordinator;
    private final WorkflowRunRepository workflowRepository;
    private final ProjectService projectService;
    private final AuthService authService;

    public BgmSelectionController(
        WorkflowExecutionService workflowService,
        WorkflowAdvanceCoordinator advanceCoordinator,
        WorkflowRunRepository workflowRepository,
        ProjectService projectService,
        AuthService authService
    ) {
        this.workflowService = workflowService;
        this.advanceCoordinator = advanceCoordinator;
        this.workflowRepository = workflowRepository;
        this.projectService = projectService;
        this.authService = authService;
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/bgm-selection")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplyResponse select(
        @PathVariable String workflowRunId,
        @Valid @RequestBody SelectBgmRequest request,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        var continuedRunId = advanceCoordinator.selectBgmCandidate(workflowRunId, request.candidateArtifactId());
        return new ApplyResponse(continuedRunId, "/api/v1/workflow-runs/" + continuedRunId);
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/bgm-selection/skip")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplyResponse skip(
        @PathVariable String workflowRunId,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        var continuedRunId = advanceCoordinator.continueWithoutBgm(workflowRunId);
        return new ApplyResponse(continuedRunId, "/api/v1/workflow-runs/" + continuedRunId);
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/bgm-selection/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplyResponse upload(
        @PathVariable String workflowRunId,
        @RequestPart("file") MultipartFile file,
        @RequestParam(defaultValue = "ONCE") String playbackMode,
        @RequestParam(defaultValue = "0") long durationMs,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        var continuedRunId = workflowService.uploadBgm(
            workflowRunId, file, playbackMode, durationMs
        );
        return new ApplyResponse(continuedRunId, "/api/v1/workflow-runs/" + continuedRunId);
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/bgm-selection/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplyResponse refresh(
        @PathVariable String workflowRunId,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        var continuedRunId = advanceCoordinator.refreshBgmCandidates(workflowRunId);
        return new ApplyResponse(continuedRunId, "/api/v1/workflow-runs/" + continuedRunId);
    }

    private void requireWorkflow(String workflowRunId, HttpServletRequest request) {
        var workflow = workflowRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        projectService.getRequiredForUser(workflow.getProjectId(), authService.requireUser(request).id());
    }

    public record SelectBgmRequest(@NotBlank String candidateArtifactId) {}
    public record ApplyResponse(String workflowRunId, String statusUrl) {}
}
