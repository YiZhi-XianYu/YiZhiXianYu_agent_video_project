package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Internal Rabbit Worker claim endpoint. Keep network-restricted in deployment. */
@RestController
@RequestMapping("/internal/tool-claims")
public class ToolClaimController {
    private final WorkflowExecutionService workflowService;
    private final String workerToken;
    public ToolClaimController(WorkflowExecutionService workflowService,
                               @Value("${app.messaging.rabbit.worker-token:}") String workerToken) {
        this.workflowService = workflowService; this.workerToken = workerToken == null ? "" : workerToken;
    }

    @PostMapping("/{workflowRunId}/{taskRunId}")
    public ClaimResponse claim(@PathVariable String workflowRunId, @PathVariable String taskRunId,
                      @RequestBody ClaimRequest request,
                      @RequestHeader(value = "X-Internal-Worker-Token", required = false) String headerToken) {
        var suppliedToken = headerToken == null || headerToken.isBlank() ? request.workerToken() : headerToken;
        if (!workerToken.isBlank() && !workerToken.equals(suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid worker token");
        }
        var accepted = workflowService.markAccepted(workflowRunId, taskRunId, request.idempotencyKey(),
            new ToolServiceClient.AcceptedExecution(request.executionId(), request.status(), null));
        return new ClaimResponse(accepted);
    }
    public record ClaimRequest(String idempotencyKey, String executionId, String status, String workerToken) {}
    public record ClaimResponse(boolean accepted) {}
}
