package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRunRepository extends JpaRepository<TaskRunEntity, String> {
    List<TaskRunEntity> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);
    List<TaskRunEntity> findByWorkflowRunIdAndAssetIdOrderByCreatedAtAsc(String workflowRunId, String assetId);
}
