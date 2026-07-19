package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "workflow_assets",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_workflow_asset", columnNames = {"workflowRunId", "assetId"}),
        @UniqueConstraint(name = "uk_workflow_asset_position", columnNames = {"workflowRunId", "positionIndex"})
    }
)
public class WorkflowAssetEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String workflowRunId;

    @Column(nullable = false, length = 40)
    private String assetId;

    @Column(nullable = false)
    private int positionIndex;

    protected WorkflowAssetEntity() {
    }

    public WorkflowAssetEntity(String workflowRunId, String assetId, int positionIndex) {
        this.workflowRunId = workflowRunId;
        this.assetId = assetId;
        this.positionIndex = positionIndex;
    }

    public String getWorkflowRunId() { return workflowRunId; }
    public String getAssetId() { return assetId; }
    public int getPositionIndex() { return positionIndex; }
}
