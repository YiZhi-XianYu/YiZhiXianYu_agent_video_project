package com.yizhixianyu.agentvideo.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentSessionTurnRepository extends JpaRepository<AgentSessionTurnEntity, String> {
    List<AgentSessionTurnEntity> findBySessionIdOrderBySequenceNumberAsc(String sessionId);
    long countBySessionId(String sessionId);
}
