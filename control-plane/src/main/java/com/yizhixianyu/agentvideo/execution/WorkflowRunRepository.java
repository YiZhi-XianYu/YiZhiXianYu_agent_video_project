package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, String> {
    List<WorkflowRunEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
}

