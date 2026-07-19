package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowAssetRepository extends JpaRepository<WorkflowAssetEntity, String> {
    List<WorkflowAssetEntity> findByWorkflowRunIdOrderByPositionIndexAsc(String workflowRunId);
}
