package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
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

    @Column(nullable = false, length = 20)
    private String dependencyType;

    protected TaskDependencyEntity() {
    }

    public TaskDependencyEntity(String taskRunId, String dependsOnTaskRunId) {
        this(taskRunId, dependsOnTaskRunId, WorkflowDefinition.DependencyType.REQUIRED);
    }

    public TaskDependencyEntity(
        String taskRunId,
        String dependsOnTaskRunId,
        WorkflowDefinition.DependencyType dependencyType
    ) {
        this.taskRunId = taskRunId;
        this.dependsOnTaskRunId = dependsOnTaskRunId;
        this.dependencyType = dependencyType.name();
    }

    public String getTaskRunId() { return taskRunId; }
    public String getDependsOnTaskRunId() { return dependsOnTaskRunId; }
    public WorkflowDefinition.DependencyType getDependencyType() {
        return WorkflowDefinition.DependencyType.valueOf(dependencyType);
    }
}
