package com.yizhixianyu.agentvideo.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, String> {
    List<AgentSessionEntity> findByProjectIdAndUserIdOrderByUpdatedAtDesc(String projectId, String userId);
}
