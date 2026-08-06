package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutionPoller {

    private final WorkflowExecutionService workflowService;
    private final WorkflowAdvanceCoordinator advanceCoordinator;
    private final ToolServiceClient toolClient;
    private final boolean rabbitEnabled;

    public ToolExecutionPoller(WorkflowExecutionService workflowService, WorkflowAdvanceCoordinator advanceCoordinator, ToolServiceClient toolClient,
                               @Value("${app.messaging.rabbit.enabled:false}") boolean rabbitEnabled) {
        this.workflowService = workflowService;
        this.advanceCoordinator = advanceCoordinator;
        this.toolClient = toolClient;
        this.rabbitEnabled = rabbitEnabled;
    }

    @Scheduled(fixedDelayString = "${app.tool-service.poll-interval-ms}")
    public void poll() {
        for (var workflowRunId : workflowService.findRunningWorkflowIds()) {
            try { advanceCoordinator.recoverWorkflow(workflowRunId); }
            catch (com.yizhixianyu.agentvideo.cache.WorkflowLockBusyException ignored) { }
        }
        // Rabbit workers have independent execution stores. Polling the shared
        // tool-service endpoint would create false 404s for non-LIGHT workers;
        // results arrive through the callback endpoint in this mode.
        if (rabbitEnabled) {
            return;
        }
        for (var execution : workflowService.findPendingToolExecutions()) {
            try {
                var response = toolClient.getExecution(execution.getExternalExecutionId());
                if (response != null) {
                    workflowService.applyToolResult(response);
                }
            } catch (Exception exc) {
                workflowService.recordPollFailure(
                    execution.getExternalExecutionId(), WorkflowExecutionService.rootMessage(exc)
                );
            }
        }
    }
}
