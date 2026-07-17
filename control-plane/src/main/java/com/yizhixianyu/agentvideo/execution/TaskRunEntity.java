package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "task_runs")
public class TaskRunEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String workflowRunId;

    @Column(nullable = false, length = 100)
    private String nodeKey;

    @Column(nullable = false, length = 120)
    private String toolName;

    @Column(nullable = false, length = 30)
    private String toolVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(nullable = false)
    private int attempt;

    private Instant startedAt;

    private Instant completedAt;

    @Column(length = 2000)
    private String errorMessage;

    protected TaskRunEntity() {
    }

    public TaskRunEntity(String workflowRunId) {
        this.workflowRunId = workflowRunId;
        this.nodeKey = "video_probe";
        this.toolName = "video.probe";
        this.toolVersion = "1.0.0";
        this.status = TaskStatus.PENDING;
        this.progress = 0;
        this.attempt = 0;
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
        errorMessage = message;
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

    public String getToolName() {
        return toolName;
    }

    public String getToolVersion() {
        return toolVersion;
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
