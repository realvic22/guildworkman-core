package com.guildworkman.api.chain.repository;

import com.guildworkman.api.chain.model.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvent o where o.status in :statuses and o.nextAttemptAt <= :now order by o.id")
    List<OutboxEvent> claimNext(@Param("statuses") Set<OutboxStatus> statuses, @Param("now") Instant now, Pageable pageable);
    Optional<OutboxEvent> findByEventId(Long eventId);
}
