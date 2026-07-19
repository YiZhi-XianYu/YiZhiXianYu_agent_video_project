package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "task_dependencies",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_task_dependency", columnNames = {"taskRunId", "dependsOnTaskRunId"}
    )
)
public class TaskDependencyEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String taskRunId;

    @Column(nullable = false, length = 40)
    private String dependsOnTaskRunId;

    protected TaskDependencyEntity() {
    }

    public TaskDependencyEntity(String taskRunId, String dependsOnTaskRunId) {
        this.taskRunId = taskRunId;
        this.dependsOnTaskRunId = dependsOnTaskRunId;
    }

    public String getTaskRunId() { return taskRunId; }
    public String getDependsOnTaskRunId() { return dependsOnTaskRunId; }
}
