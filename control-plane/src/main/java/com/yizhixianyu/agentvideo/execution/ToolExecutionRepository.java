package com.yizhixianyu.agentvideo.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, String> {
    Optional<ToolExecutionEntity> findByExternalExecutionId(String externalExecutionId);
    List<ToolExecutionEntity> findByStatusIn(List<String> statuses);
}

