package com.yizhixianyu.agentvideo.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomStoryPlanRepository extends JpaRepository<CustomStoryPlanEntity, String> {
    Optional<CustomStoryPlanEntity> findBySourceWorkflowRunIdAndStatus(String sourceWorkflowRunId, String status);
    List<CustomStoryPlanEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
    List<CustomStoryPlanEntity> findBySourceWorkflowRunIdOrderByCreatedAtDesc(String sourceWorkflowRunId);
    Optional<CustomStoryPlanEntity> findByIdAndSourceWorkflowRunId(String id, String sourceWorkflowRunId);
}
