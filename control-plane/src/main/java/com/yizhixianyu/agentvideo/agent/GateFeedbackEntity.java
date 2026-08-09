package com.yizhixianyu.agentvideo.agent;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "gate_feedback")
public class GateFeedbackEntity extends BaseEntity {
    @Column(nullable = false, length = 40)
    private String workflowRunId;
    @Column(nullable = false, length = 40)
    private String projectId;
    @Column(nullable = false, length = 100)
    private String gateKey;
    @Column(nullable = false)
    private int score;
    @Column(length = 40)
    private String action;
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String reasonCodesJson;
    @Column(length = 2000)
    private String comment;
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String artifactIdsJson;

    protected GateFeedbackEntity() {}

    public GateFeedbackEntity(String workflowRunId, String projectId, String gateKey, int score,
                              String action, String reasonCodesJson, String comment, String artifactIdsJson) {
        this.workflowRunId = workflowRunId;
        this.projectId = projectId;
        this.gateKey = gateKey;
        this.score = score;
        this.action = action;
        this.reasonCodesJson = reasonCodesJson;
        this.comment = comment;
        this.artifactIdsJson = artifactIdsJson;
    }

    public String getWorkflowRunId() { return workflowRunId; }
    public String getProjectId() { return projectId; }
    public String getGateKey() { return gateKey; }
    public int getScore() { return score; }
    public String getAction() { return action; }
    public String getReasonCodesJson() { return reasonCodesJson; }
    public String getComment() { return comment; }
    public String getArtifactIdsJson() { return artifactIdsJson; }
}
