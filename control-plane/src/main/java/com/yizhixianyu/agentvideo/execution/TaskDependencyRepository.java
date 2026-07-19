package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskDependencyRepository extends JpaRepository<TaskDependencyEntity, String> {
    List<TaskDependencyEntity> findByTaskRunId(String taskRunId);
    List<TaskDependencyEntity> findByTaskRunIdIn(List<String> taskRunIds);
    List<TaskDependencyEntity> findByDependsOnTaskRunId(String dependsOnTaskRunId);
}
