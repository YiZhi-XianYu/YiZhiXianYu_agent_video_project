package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_session_turns")
public class AgentSessionTurnEntity extends BaseEntity {
    @Column(nullable = false, length = 40) private String sessionId;
    @Column(nullable = false) private int sequenceNumber;
    @Column(nullable = false, length = 20) private String role;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String content;
    @Column(length = 80) private String planId;
    @Column(length = 40) private String workflowRunId;

    protected AgentSessionTurnEntity() {}
    public AgentSessionTurnEntity(String sessionId, int sequenceNumber, String role, String content) {
        this.sessionId = sessionId;
        this.sequenceNumber = sequenceNumber;
        this.role = role;
        this.content = content == null ? "" : content;
    }
    public void linkPlan(String planId) { this.planId = planId; }
    public void linkWorkflow(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getSessionId() { return sessionId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getPlanId() { return planId; }
    public String getWorkflowRunId() { return workflowRunId; }
}
