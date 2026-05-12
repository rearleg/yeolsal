package com.yeosal.api.survival;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PendingRealtimeBroadcastRepository
        extends JpaRepository<PendingRealtimeBroadcast, Long> {

    /**
     * Rows whose {@code scheduled_at} has matured AND have not yet been
     * emitted. The matching partial index
     * {@code idx_pending_realtime_due} keeps the dispatcher tick cheap
     * even as the table grows.
     */
    @Query("""
            select p from PendingRealtimeBroadcast p
            where p.scheduledAt <= :now and p.emittedAt is null
            order by p.scheduledAt asc, p.id asc
            """)
    List<PendingRealtimeBroadcast> findDueForEmission(
            @Param("now") Instant now, Pageable pageable);

    /**
     * Atomic transition from "due" to "emitted". Returns 1 on success, 0 if
     * another dispatcher tick beat us (defense for ad-hoc replay or future
     * multi-instance fan-out). The {@code WHERE emitted_at IS NULL}
     * predicate makes this CAS-style.
     */
    @Modifying
    @Query("""
            update PendingRealtimeBroadcast p
            set p.emittedAt = :emittedAt
            where p.id = :id and p.emittedAt is null
            """)
    int markEmitted(@Param("id") long id, @Param("emittedAt") Instant emittedAt);
}
