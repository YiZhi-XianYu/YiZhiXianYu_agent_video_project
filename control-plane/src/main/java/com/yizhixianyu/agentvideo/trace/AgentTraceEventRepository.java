package com.yizhixianyu.agentvideo.trace;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentTraceEventRepository extends JpaRepository<AgentTraceEventEntity, String> {
    List<AgentTraceEventEntity> findByWorkflowRunIdOrderByOccurredAtAsc(String workflowRunId);
    List<AgentTraceEventEntity> findBySessionIdOrderByOccurredAtAsc(String sessionId);
    List<AgentTraceEventEntity> findByTaskRunIdOrderByOccurredAtAsc(String taskRunId);
    List<AgentTraceEventEntity> findTop500ByTraceIdOrderByOccurredAtAsc(String traceId);
}
