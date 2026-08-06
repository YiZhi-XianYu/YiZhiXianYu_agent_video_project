package com.yizhixianyu.agentvideo.trace;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_trace_events")
public class AgentTraceEventEntity extends BaseEntity {
    @Column(nullable = false, length = 80) private String eventType;
    @Column(nullable = false, length = 80) private String traceId;
    @Column(length = 80) private String sessionId;
    @Column(length = 80) private String turnId;
    @Column(length = 80) private String planId;
    @Column(length = 40) private String workflowRunId;
    @Column(length = 40) private String taskRunId;
    @Column(length = 80) private String messageId;
    @Column(length = 80) private String executionId;
    @Column(length = 120) private String agentName;
    @Column(length = 120) private String toolName;
    @Column(length = 30) private String status;
    @Column(nullable = false) private Instant occurredAt;
    @Lob @Column(columnDefinition = "LONGTEXT") private String payloadJson;

    protected AgentTraceEventEntity() {}

    public AgentTraceEventEntity(String eventType, String traceId, String sessionId, String turnId,
                                 String planId, String workflowRunId, String taskRunId,
                                 String messageId, String executionId, String agentName,
                                 String toolName, String status, String payloadJson) {
        this.eventType = eventType;
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.planId = planId;
        this.workflowRunId = workflowRunId;
        this.taskRunId = taskRunId;
        this.messageId = messageId;
        this.executionId = executionId;
        this.agentName = agentName;
        this.toolName = toolName;
        this.status = status;
        this.occurredAt = Instant.now();
        this.payloadJson = payloadJson;
    }

    public String getEventType() { return eventType; }
    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public String getTurnId() { return turnId; }
    public String getPlanId() { return planId; }
    public String getWorkflowRunId() { return workflowRunId; }
    public String getTaskRunId() { return taskRunId; }
    public String getMessageId() { return messageId; }
    public String getExecutionId() { return executionId; }
    public String getAgentName() { return agentName; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPayloadJson() { return payloadJson; }
}
