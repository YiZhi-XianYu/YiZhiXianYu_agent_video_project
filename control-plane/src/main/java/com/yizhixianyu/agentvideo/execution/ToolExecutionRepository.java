package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, String> {
    Optional<ToolExecutionEntity> findByExternalExecutionId(String externalExecutionId);
    Optional<ToolExecutionEntity> findByTaskRunIdAndIdempotencyKey(String taskRunId, String idempotencyKey);
    List<ToolExecutionEntity> findByStatusIn(List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from ToolExecutionEntity execution where execution.externalExecutionId = :externalId")
    Optional<ToolExecutionEntity> findLockedByExternalExecutionId(@Param("externalId") String externalId);

    @Query("select task.workflowRunId from ToolExecutionEntity execution, TaskRunEntity task "
        + "where execution.taskRunId = task.id and execution.externalExecutionId = :externalId")
    Optional<String> findWorkflowRunIdByExternalExecutionId(@Param("externalId") String externalId);
}
