package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.beans.factory.annotation.Value;

@Component
public class WorkflowDispatchListener {

    private final WorkflowExecutionService workflowService;
    private final RabbitDispatchService rabbitDispatchService;
    private final ToolServiceClient toolClient;
    private final boolean rabbitEnabled;

    public WorkflowDispatchListener(WorkflowExecutionService workflowService, ToolServiceClient toolClient,
                                    RabbitDispatchService rabbitDispatchService,
                                    @Value("${app.messaging.rabbit.enabled:false}") boolean rabbitEnabled) {
        this.workflowService = workflowService;
        this.rabbitDispatchService = rabbitDispatchService;
        this.toolClient = toolClient;
        this.rabbitEnabled = rabbitEnabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispatchRequested(WorkflowExecutionService.WorkflowDispatchRequested event) {
        try {
            var context = rabbitEnabled
                ? rabbitDispatchService.prepareAndEnqueue(event.workflowRunId(), event.taskRunId())
                : workflowService.prepareDispatch(event.workflowRunId(), event.taskRunId());
            if (context == null) {
                return;
            }
            if (rabbitEnabled) return;
            var accepted = toolClient.createExecution(context.request());
            workflowService.markAccepted(
                event.workflowRunId(), event.taskRunId(), context.idempotencyKey(), accepted
            );
        } catch (Exception exc) {
            var message = WorkflowExecutionService.rootMessage(exc);
            workflowService.markDispatchFailed(
                event.workflowRunId(), event.taskRunId(), message
            );
        }
    }

}
