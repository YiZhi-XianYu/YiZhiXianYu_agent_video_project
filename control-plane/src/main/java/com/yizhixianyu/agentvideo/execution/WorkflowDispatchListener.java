package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkflowDispatchListener {

    private final WorkflowExecutionService workflowService;
    private final ToolServiceClient toolClient;

    public WorkflowDispatchListener(WorkflowExecutionService workflowService, ToolServiceClient toolClient) {
        this.workflowService = workflowService;
        this.toolClient = toolClient;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispatchRequested(WorkflowExecutionService.WorkflowDispatchRequested event) {
        try {
            var context = workflowService.prepareDispatch(event.workflowRunId(), event.taskRunId());
            if (context == null) {
                return;
            }
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
