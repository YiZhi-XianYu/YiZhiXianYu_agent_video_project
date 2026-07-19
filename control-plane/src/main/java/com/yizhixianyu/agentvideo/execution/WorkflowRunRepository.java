package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, String> {
    List<WorkflowRunEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
    List<WorkflowRunEntity> findByStatus(RunStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workflow from WorkflowRunEntity workflow where workflow.id = :id")
    Optional<WorkflowRunEntity> findLockedById(@Param("id") String id);
}
