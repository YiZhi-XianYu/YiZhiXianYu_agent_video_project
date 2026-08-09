package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_plan_snapshots")
public class AgentPlanSnapshotEntity extends BaseEntity {
    @Column(nullable = false, length = 40) private String sessionId;
    @Column(nullable = false, length = 80) private String turnId;
    @Column(nullable = false, length = 40) private String projectId;
    @Column(nullable = false, length = 80) private String traceId;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false, length = 30) private String quality;
    @Column(nullable = false) private boolean autoMode;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String goal;
    @Column(nullable = false) private int targetDurationMs;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String assetIdsJson;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String definitionJson;

    protected AgentPlanSnapshotEntity() {}

    public AgentPlanSnapshotEntity(String sessionId, String turnId, String projectId, String traceId,
                                   String quality, boolean autoMode, String goal, int targetDurationMs,
                                   String assetIdsJson, String definitionJson) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.projectId = projectId;
        this.traceId = traceId;
        this.status = "PROPOSED";
        this.quality = quality;
        this.autoMode = autoMode;
        this.goal = goal;
        this.targetDurationMs = targetDurationMs;
        this.assetIdsJson = assetIdsJson;
        this.definitionJson = definitionJson;
    }

    public void confirm() { this.status = "CONFIRMED"; }
    public String getSessionId() { return sessionId; }
    public String getTurnId() { return turnId; }
    public String getProjectId() { return projectId; }
    public String getTraceId() { return traceId; }
    public String getStatus() { return status; }
    public String getQuality() { return quality; }
    public boolean isAutoMode() { return autoMode; }
    public String getGoal() { return goal; }
    public int getTargetDurationMs() { return targetDurationMs; }
    public String getAssetIdsJson() { return assetIdsJson; }
    public String getDefinitionJson() { return definitionJson; }
}
