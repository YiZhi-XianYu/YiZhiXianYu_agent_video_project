package com.yizhixianyu.agentvideo.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentTraceService {
    private final AgentTraceEventRepository repository;
    private final ObjectMapper mapper;

    public AgentTraceService(AgentTraceEventRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public AgentTraceEventEntity record(String eventType, String traceId, String sessionId, String turnId,
                                        String planId, String workflowRunId, String taskRunId,
                                        String messageId, String executionId, String agentName,
                                        String toolName, String status, Map<String, Object> payload) {
        var effectiveTrace = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        try {
            return repository.save(new AgentTraceEventEntity(eventType, effectiveTrace, sessionId, turnId, planId,
                workflowRunId, taskRunId, messageId, executionId, agentName, toolName, status,
                payload == null ? "{}" : mapper.writeValueAsString(payload)));
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to persist Agent Trace event", exc);
        }
    }

    @Transactional(readOnly = true)
    public List<AgentTraceEventEntity> byWorkflow(String workflowRunId) {
        return repository.findByWorkflowRunIdOrderByOccurredAtAsc(workflowRunId);
    }

    @Transactional(readOnly = true)
    public List<AgentTraceEventEntity> bySession(String sessionId) {
        return repository.findBySessionIdOrderByOccurredAtAsc(sessionId);
    }
}
