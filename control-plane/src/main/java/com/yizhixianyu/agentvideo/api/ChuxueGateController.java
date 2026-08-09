package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.agent.ChuxueGateService;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** User-facing Gate collaboration API for Chuxue. */
@RestController
@RequestMapping("/api/v1/workflow-runs/{workflowRunId}/chuxue-gate")
public class ChuxueGateController {
    private final ChuxueGateService gates;
    private final WorkflowExecutionService workflows;
    private final AuthService auth;
    private final ProjectService projects;

    public ChuxueGateController(ChuxueGateService gates, WorkflowExecutionService workflows, AuthService auth, ProjectService projects) {
        this.gates = gates; this.workflows = workflows; this.auth = auth; this.projects = projects;
    }

    @GetMapping
    public ChuxueGateService.GateView current(@PathVariable String workflowRunId, HttpServletRequest request) {
        var view = gates.current(workflowRunId);
        requireProject(view.workflowRunId(), request);
        return view;
    }

    @PostMapping("/decision")
    public ChuxueGateService.GateView decide(@PathVariable String workflowRunId,
                                             @Valid @RequestBody ChuxueGateService.DecisionRequest decision,
                                             HttpServletRequest request) {
        var view = gates.current(workflowRunId);
        requireProject(view.workflowRunId(), request);
        return gates.decide(workflowRunId, decision);
    }

    @PostMapping("/feedback")
    public ChuxueGateService.FeedbackView feedback(@PathVariable String workflowRunId,
                                                   @RequestBody ChuxueGateService.FeedbackRequest feedback,
                                                   HttpServletRequest request) {
        var view = gates.current(workflowRunId);
        requireProject(view.workflowRunId(), request);
        return gates.feedback(workflowRunId, feedback);
    }

    private void requireProject(String workflowRunId, HttpServletRequest request) {
        var snapshot = workflows.getSnapshot(workflowRunId);
        projects.getRequiredForUser(snapshot.projectId(), auth.requireUser(request).id());
    }
}
