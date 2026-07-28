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
@Table(name = "task_runs")
public class TaskRunEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String workflowRunId;

    @Column(nullable = false, length = 100)
    private String nodeKey;

    @Column(length = 40)
    private String assetId;

    @Column(length = 180)
    private String instanceKey;

    @Column(nullable = false, length = 120)
    private String toolName;

    @Column(nullable = false, length = 30)
    private String toolVersion;

    @Column(length = 40)
    private String inputBinding;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String parametersJson;

    @Column(length = 40)
    private String dependsOnTaskRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private int retryCount;

    private Instant startedAt;

    private Instant completedAt;

    private Instant nextAttemptAt;

    @Column(nullable = false)
    private boolean retrySameAttempt;

    @Column(length = 2000)
    private String errorMessage;

    protected TaskRunEntity() {
    }

    public TaskRunEntity(
        String workflowRunId,
        String nodeKey,
        String toolName,
        String toolVersion,
        String dependsOnTaskRunId
    ) {
        this.workflowRunId = workflowRunId;
        this.nodeKey = nodeKey;
        this.toolName = toolName;
        this.toolVersion = toolVersion;
        this.inputBinding = "PROJECT_ASSET";
        this.parametersJson = "{}";
        this.dependsOnTaskRunId = dependsOnTaskRunId;
        this.status = TaskStatus.PENDING;
        this.progress = 0;
        this.attempt = 0;
        this.retryCount = 0;
        this.retrySameAttempt = false;
    }

    public TaskRunEntity(
        String workflowRunId,
        String assetId,
        String instanceKey,
        String nodeKey,
        String toolName,
        String toolVersion,
        String inputBinding,
        String parametersJson
    ) {
        this.workflowRunId = workflowRunId;
        this.assetId = assetId;
        this.instanceKey = instanceKey;
        this.nodeKey = nodeKey;
        this.toolName = toolName;
        this.toolVersion = toolVersion;
        this.inputBinding = inputBinding;
        this.parametersJson = parametersJson;
        this.dependsOnTaskRunId = null;
        this.status = TaskStatus.PENDING;
        this.progress = 0;
        this.attempt = 0;
        this.retryCount = 0;
        this.retrySameAttempt = false;
    }

    public void markReady() {
        require(TaskStatus.PENDING);
        status = TaskStatus.READY;
    }

    public void markDispatching() {
        require(TaskStatus.READY);
        status = TaskStatus.DISPATCHING;
        attempt += 1;
        progress = 5;
        errorMessage = null;
        nextAttemptAt = null;
    }

    public void resumeDispatching() {
        require(TaskStatus.DISPATCHING);
        progress = 5;
        errorMessage = null;
        nextAttemptAt = null;
    }

    public void markRunning() {
        if (status != TaskStatus.DISPATCHING && status != TaskStatus.RUNNING) {
            throw new IllegalStateException("Cannot mark task RUNNING from " + status);
        }
        status = TaskStatus.RUNNING;
        progress = Math.max(progress, 10);
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public void updateProgress(int progress) {
        if (status != TaskStatus.RUNNING) {
            return;
        }
        this.progress = Math.max(this.progress, Math.min(progress, 99));
    }

    public void markSucceeded() {
        if (status == TaskStatus.SUCCEEDED) {
            return;
        }
        if (status != TaskStatus.RUNNING && status != TaskStatus.DISPATCHING) {
            throw new IllegalStateException("Cannot succeed task from " + status);
        }
        status = TaskStatus.SUCCEEDED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = null;
    }

    public void markFailed(String message) {
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED) {
            return;
        }
        status = TaskStatus.FAILED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = ErrorMessageFormatter.fit(message);
        nextAttemptAt = null;
    }

    public void scheduleRetry(String message, Instant retryAt, boolean sameAttempt) {
        if (status != TaskStatus.DISPATCHING && status != TaskStatus.RUNNING) {
            throw new IllegalStateException("Cannot retry task from " + status);
        }
        status = TaskStatus.RETRY_WAIT;
        progress = 0;
        completedAt = null;
        errorMessage = ErrorMessageFormatter.fit(message);
        nextAttemptAt = retryAt;
        retrySameAttempt = sameAttempt;
        retryCount += 1;
    }

    public boolean releaseRetry(Instant now) {
        require(TaskStatus.RETRY_WAIT);
        if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            throw new IllegalStateException("Task retry is not due yet");
        }
        var resumeAttempt = retrySameAttempt;
        status = resumeAttempt ? TaskStatus.DISPATCHING : TaskStatus.READY;
        progress = 0;
        errorMessage = null;
        nextAttemptAt = null;
        retrySameAttempt = false;
        return resumeAttempt;
    }

    public void markSkipped(String message) {
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED || status == TaskStatus.SKIPPED) {
            return;
        }
        status = TaskStatus.SKIPPED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = ErrorMessageFormatter.fit(message);
    }

    public void resetForReexecution() {
        if (status == TaskStatus.DISPATCHING || status == TaskStatus.RUNNING || status == TaskStatus.RETRY_WAIT) {
            throw new IllegalStateException("Cannot reset active task " + nodeKey + " from " + status);
        }
        status = TaskStatus.PENDING;
        progress = 0;
        retryCount = 0;
        startedAt = null;
        completedAt = null;
        nextAttemptAt = null;
        retrySameAttempt = false;
        errorMessage = null;
    }

    private void require(TaskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected task status " + expected + " but was " + status);
        }
    }

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getAssetId() { return assetId; }
    public String getInstanceKey() { return instanceKey; }

    public String getToolName() {
        return toolName;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public String getInputBinding() { return inputBinding; }
    public String getParametersJson() { return parametersJson; }

    public void updateParametersJson(String parametersJson) {
        if (status == TaskStatus.DISPATCHING || status == TaskStatus.RUNNING || status == TaskStatus.RETRY_WAIT) {
            throw new IllegalStateException("Cannot update parameters for active task " + nodeKey);
        }
        this.parametersJson = parametersJson == null || parametersJson.isBlank() ? "{}" : parametersJson;
    }

    public String getDependsOnTaskRunId() {
        return dependsOnTaskRunId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public boolean canRetry(int maxAttempts) {
        return retryCount < maxAttempts - 1;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
