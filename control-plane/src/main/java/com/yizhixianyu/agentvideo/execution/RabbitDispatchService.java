package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;

/**
 * Prepares a task and persists its Outbox record in the same database
 * transaction. The listener invokes this only after the workflow transaction
 * has committed, but the task transition and message creation remain atomic.
 */
@Service
public class RabbitDispatchService {
    private final WorkflowExecutionService workflowService;
    private final OutboxService outboxService;

    public RabbitDispatchService(WorkflowExecutionService workflowService, OutboxService outboxService) {
        this.workflowService = workflowService;
        this.outboxService = outboxService;
    }

    @Transactional
    public WorkflowExecutionService.DispatchContext prepareAndEnqueue(String workflowRunId, String taskRunId) {
        var context = workflowService.prepareDispatch(workflowRunId, taskRunId);
        if (context == null) return null;
        var request = context.request();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", "1.0");
        payload.put("workflowRunId", workflowRunId);
        payload.put("taskRunId", taskRunId);
        payload.put("attempt", context.attempt());
        payload.put("traceId", request.traceContext() == null ? null : request.traceContext().traceId());
        payload.put("resourceGroup", resourceGroup(request.tool()));
        payload.put("request", request);
        outboxService.enqueueTask(workflowRunId, taskRunId, payload);
        return context;
    }

    private String resourceGroup(String tool) {
        if (tool == null) return "LIGHT";
        if (tool.startsWith("video.render")) return "RENDER";
        if (tool.startsWith("vision.") || tool.startsWith("audio.source-transcribe")) return "MODEL";
        if (tool.startsWith("video.")) return "MEDIA";
        return "LIGHT";
    }
}
