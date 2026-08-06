package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_sessions")
public class AgentSessionEntity extends BaseEntity {
    @Column(nullable = false, length = 40) private String userId;
    @Column(nullable = false, length = 40) private String projectId;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String naturalLanguageGoal;
    private Integer targetDurationMs;
    @Column(length = 40) private String currentWorkflowRunId;
    @Column(length = 80) private String currentTurnId;
    @Column(length = 80) private String currentPlanId;
    private Integer dagVersion;
    @Column(length = 100) private String currentGateKey;
    @Column(nullable = false, length = 30) private String status;

    protected AgentSessionEntity() {}

    public AgentSessionEntity(String userId, String projectId, String goal, Integer targetDurationMs) {
        this.userId = userId;
        this.projectId = projectId;
        this.naturalLanguageGoal = goal == null ? "" : goal.trim();
        this.targetDurationMs = targetDurationMs;
        this.status = "PLANNING";
    }

    public void updateGoal(String goal, Integer targetDurationMs, String turnId) {
        if (goal != null && !goal.isBlank()) this.naturalLanguageGoal = goal.trim();
        if (targetDurationMs != null) this.targetDurationMs = targetDurationMs;
        this.currentTurnId = turnId;
        if (!"EXECUTING".equals(status) && !"WAITING_GATE".equals(status)) status = "PLANNING";
    }

    public void recordPlan(String turnId, String planId, int dagVersion) {
        this.currentTurnId = turnId;
        this.currentPlanId = planId;
        this.dagVersion = dagVersion;
        this.status = "PLAN_READY";
    }

    public void attachWorkflow(String workflowRunId, String turnId, String planId, int dagVersion) {
        this.currentWorkflowRunId = workflowRunId;
        this.currentTurnId = turnId;
        this.currentPlanId = planId;
        this.dagVersion = dagVersion;
        this.currentGateKey = null;
        this.status = "EXECUTING";
    }

    public void syncRuntime(String workflowStatus, String gateKey) {
        this.currentGateKey = gateKey;
        this.status = switch (workflowStatus) {
            case "PAUSED" -> "WAITING_GATE";
            case "SUCCEEDED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            default -> currentWorkflowRunId == null ? status : "EXECUTING";
        };
    }

    public String getUserId() { return userId; }
    public String getProjectId() { return projectId; }
    public String getNaturalLanguageGoal() { return naturalLanguageGoal; }
    public Integer getTargetDurationMs() { return targetDurationMs; }
    public String getCurrentWorkflowRunId() { return currentWorkflowRunId; }
    public String getCurrentTurnId() { return currentTurnId; }
    public String getCurrentPlanId() { return currentPlanId; }
    public Integer getDagVersion() { return dagVersion; }
    public String getCurrentGateKey() { return currentGateKey; }
    public String getStatus() { return status; }
}
