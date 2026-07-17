package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tool_executions")
public class ToolExecutionEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String taskRunId;

    @Column(nullable = false, unique = true, length = 120)
    private String idempotencyKey;

    @Column(nullable = false, length = 80)
    private String externalExecutionId;

    @Column(nullable = false, length = 30)
    private String status;

    protected ToolExecutionEntity() {
    }

    public ToolExecutionEntity(String taskRunId, String idempotencyKey, String externalExecutionId, String status) {
        this.taskRunId = taskRunId;
        this.idempotencyKey = idempotencyKey;
        this.externalExecutionId = externalExecutionId;
        this.status = status;
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    public String getTaskRunId() {
        return taskRunId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getExternalExecutionId() {
        return externalExecutionId;
    }

    public String getStatus() {
        return status;
    }
}
