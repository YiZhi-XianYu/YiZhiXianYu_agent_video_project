package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.outbox.OutboxService;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prepares a task and persists its Outbox record in the same database
 * transaction. The listener invokes this only after the workflow transaction
 * has committed, but the task transition and message creation remain atomic.
 */
@Service
public class RabbitDispatchService {
    private final WorkflowExecutionService workflowService;
    private final OutboxService outboxService;
    private final AgentTraceService traceService;

    public RabbitDispatchService(WorkflowExecutionService workflowService, OutboxService outboxService, AgentTraceService traceService) {
        this.workflowService = workflowService;
        this.outboxService = outboxService;
        this.traceService = traceService;
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
        var outbox = outboxService.enqueueTask(workflowRunId, taskRunId, payload);
        var trace = request.traceContext();
        traceService.record("TASK_ENQUEUED", trace == null ? null : trace.traceId(), trace == null ? null : trace.sessionId(),
            trace == null ? null : trace.turnId(), trace == null ? null : trace.planId(), workflowRunId, taskRunId,
            outbox.getMessageId(), null, "workflow-dispatcher", request.tool(), "PENDING",
            Map.of("attempt", context.attempt(), "resourceGroup", resourceGroup(request.tool())));
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
