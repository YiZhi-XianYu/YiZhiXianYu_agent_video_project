package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Lob;

import java.time.Instant;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String projectId;

    @Column(length = 40)
    private String assetId;

    @Column(nullable = false, length = 100)
    private String workflowType;

    @Column(length = 20)
    private String proxyQuality;

    @Column(length = 100)
    private String definitionKey;

    private Integer definitionVersion;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunStatus status;

    @Column(nullable = false)
    private int progress;

    private Instant startedAt;

    private Instant completedAt;

    @Column(length = 2000)
    private String errorMessage;

    protected WorkflowRunEntity() {
    }

    public WorkflowRunEntity(String projectId, String assetId, String workflowType, ProxyQuality proxyQuality) {
        this.projectId = projectId;
        this.assetId = assetId;
        this.workflowType = workflowType;
        this.proxyQuality = proxyQuality.value();
        this.status = RunStatus.CREATED;
        this.progress = 0;
    }

    public WorkflowRunEntity(
        String projectId,
        String legacyAssetId,
        String workflowType,
        ProxyQuality proxyQuality,
        String definitionKey,
        int definitionVersion,
        String definitionJson
    ) {
        this.projectId = projectId;
        // Keep the first Asset in the legacy column for databases created before multi-asset support.
        this.assetId = legacyAssetId;
        this.workflowType = workflowType;
        this.proxyQuality = proxyQuality.value();
        this.definitionKey = definitionKey;
        this.definitionVersion = definitionVersion;
        this.definitionJson = definitionJson;
        this.status = RunStatus.CREATED;
        this.progress = 0;
    }

    public void start() {
        status = RunStatus.RUNNING;
        if (progress == 0) {
            progress = 5;
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public void updateProgress(int progress) {
        if (status == RunStatus.RUNNING) {
            this.progress = Math.max(this.progress, Math.min(progress, 99));
        }
    }

    public void succeed() {
        status = RunStatus.SUCCEEDED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = null;
    }

    public void fail(String message) {
        status = RunStatus.FAILED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = message;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getAssetId() {
        return assetId;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public ProxyQuality getProxyQuality() {
        return proxyQuality == null ? ProxyQuality.FHD_1080P : ProxyQuality.fromValue(proxyQuality);
    }

    public String getDefinitionKey() { return definitionKey; }
    public Integer getDefinitionVersion() { return definitionVersion; }
    public String getDefinitionJson() { return definitionJson; }

    public RunStatus getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
