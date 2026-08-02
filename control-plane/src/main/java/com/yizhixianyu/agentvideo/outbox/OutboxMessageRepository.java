package com.yizhixianyu.agentvideo.outbox;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessageEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxMessageEntity o where o.status in ('PENDING','FAILED') and (o.nextAttemptAt is null or o.nextAttemptAt <= :now) order by o.createdAt")
    List<OutboxMessageEntity> findDue(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);
}
