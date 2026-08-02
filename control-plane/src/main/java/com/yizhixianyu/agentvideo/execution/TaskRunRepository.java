package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface TaskRunRepository extends JpaRepository<TaskRunEntity, String> {
    List<TaskRunEntity> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);
    List<TaskRunEntity> findByWorkflowRunIdAndAssetIdOrderByCreatedAtAsc(String workflowRunId, String assetId);
    List<TaskRunEntity> findByWorkflowRunIdInOrderByCreatedAtAsc(List<String> workflowRunIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from TaskRunEntity task where task.id = :id")
    Optional<TaskRunEntity> findLockedById(@Param("id") String id);
}
