package com.yizhixianyu.agentvideo.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GateFeedbackRepository extends JpaRepository<GateFeedbackEntity, String> {
    List<GateFeedbackEntity> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);
    List<GateFeedbackEntity> findByProjectIdAndGateKeyOrderByCreatedAtDesc(String projectId, String gateKey);
}
