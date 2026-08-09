package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventEntity;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import com.yizhixianyu.agentvideo.agent.ChuxueExplanationService;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/agent-traces")
public class AgentTraceController {
    private final AgentTraceService traceService;
    private final WorkflowExecutionService workflowService;
    private final AuthService authService;
    private final ProjectService projectService;
    private final ChuxueExplanationService explanations;
    private final TaskRunRepository tasks;

    public AgentTraceController(AgentTraceService traceService, WorkflowExecutionService workflowService,
                                AuthService authService, ProjectService projectService,
                                ChuxueExplanationService explanations, TaskRunRepository tasks) {
        this.traceService = traceService;
        this.workflowService = workflowService;
        this.authService = authService;
        this.projectService = projectService;
        this.explanations = explanations; this.tasks = tasks;
    }

    @GetMapping("/sessions/{sessionId}/explanation")
    public ChuxueExplanationService.Explanation bySessionExplanation(@PathVariable String sessionId, HttpServletRequest request) {
        authService.requireUser(request);
        return explanations.bySession(sessionId);
    }

    @GetMapping("/workflow-runs/{workflowRunId}/explanation")
    public ChuxueExplanationService.Explanation byWorkflowExplanation(@PathVariable String workflowRunId, HttpServletRequest request) {
        var snapshot = workflowService.getSnapshot(workflowRunId);
        projectService.getRequiredForUser(snapshot.projectId(), authService.requireUser(request).id());
        return explanations.byWorkflow(workflowRunId);
    }

    @GetMapping("/tasks/{taskRunId}/explanation")
    public ChuxueExplanationService.Explanation byTaskExplanation(@PathVariable String taskRunId, HttpServletRequest request) {
        var task = tasks.findById(taskRunId).orElseThrow();
        var snapshot = workflowService.getSnapshot(task.getWorkflowRunId());
        projectService.getRequiredForUser(snapshot.projectId(), authService.requireUser(request).id());
        return explanations.byTask(taskRunId);
    }

    @GetMapping("/workflow-runs/{workflowRunId}")
    public List<TraceEvent> byWorkflow(@PathVariable String workflowRunId, HttpServletRequest request) {
        var snapshot = workflowService.getSnapshot(workflowRunId);
        projectService.getRequiredForUser(snapshot.projectId(), authService.requireUser(request).id());
        return traceService.byWorkflow(workflowRunId).stream().map(TraceEvent::from).toList();
    }

    public record TraceEvent(String id, String eventType, String traceId, String sessionId, String turnId,
                             String planId, String workflowRunId, String taskRunId, String messageId,
                             String executionId, String agentName, String toolName, String status,
                             Instant occurredAt, String payloadJson) {
        static TraceEvent from(AgentTraceEventEntity e) {
            return new TraceEvent(e.getId(), e.getEventType(), e.getTraceId(), e.getSessionId(), e.getTurnId(),
                e.getPlanId(), e.getWorkflowRunId(), e.getTaskRunId(), e.getMessageId(), e.getExecutionId(),
                e.getAgentName(), e.getToolName(), e.getStatus(), e.getOccurredAt(), e.getPayloadJson());
        }
    }
}
