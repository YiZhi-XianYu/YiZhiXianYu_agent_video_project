package com.yizhixianyu.agentvideo.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AgentPlanSnapshotRepository extends JpaRepository<AgentPlanSnapshotEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AgentPlanSnapshotEntity p where p.id = :id")
    Optional<AgentPlanSnapshotEntity> findLockedById(String id);
}
