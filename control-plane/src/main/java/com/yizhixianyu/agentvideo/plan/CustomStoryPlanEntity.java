package com.yizhixianyu.agentvideo.plan;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "custom_story_plans")
public class CustomStoryPlanEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String projectId;

    @Column(nullable = false, length = 40)
    private String sourceWorkflowRunId;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String planJson;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = true, length = 200)
    private String versionName;

    protected CustomStoryPlanEntity() {
    }

    public CustomStoryPlanEntity(String projectId, String sourceWorkflowRunId, String planJson, String status) {
        this.projectId = projectId;
        this.sourceWorkflowRunId = sourceWorkflowRunId;
        this.planJson = planJson;
        this.status = status;
    }

    public CustomStoryPlanEntity(String projectId, String sourceWorkflowRunId, String planJson, String status, String versionName) {
        this(projectId, sourceWorkflowRunId, planJson, status);
        this.versionName = versionName;
    }

    public void setStatus(String status) { this.status = status; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public String getProjectId() { return projectId; }
    public String getSourceWorkflowRunId() { return sourceWorkflowRunId; }
    public String getPlanJson() { return planJson; }
    public String getStatus() { return status; }
    public String getVersionName() { return versionName; }
}
