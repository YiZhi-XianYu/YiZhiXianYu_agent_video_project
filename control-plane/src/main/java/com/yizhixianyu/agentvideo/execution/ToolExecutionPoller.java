package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutionPoller {

    private final WorkflowExecutionService workflowService;
    private final ToolServiceClient toolClient;

    public ToolExecutionPoller(WorkflowExecutionService workflowService, ToolServiceClient toolClient) {
        this.workflowService = workflowService;
        this.toolClient = toolClient;
    }

    @Scheduled(fixedDelayString = "${app.tool-service.poll-interval-ms}")
    public void poll() {
        for (var execution : workflowService.findPendingToolExecutions()) {
            try {
                var response = toolClient.getExecution(execution.getExternalExecutionId());
                if (response != null) {
                    workflowService.applyToolResult(response);
                }
            } catch (Exception ignored) {
                // Temporary Tool Service outages are retried by the next scan.
            }
        }
    }
}

