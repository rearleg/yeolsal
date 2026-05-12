package com.yeosal.api.survival;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurvivalStateRepository extends JpaRepository<SurvivalState, Long> {

    Optional<SurvivalState> findByRoomIdAndUserId(long roomId, long userId);

    List<SurvivalState> findByRoomId(long roomId);

    /**
     * Race-safe upsert for the (room_id, user_id) pair. Mirrors the V8/V9
     * milestone-dedup pattern (project-context): pushing dedup down to
     * Postgres via {@code ON CONFLICT DO NOTHING} is stronger than catching
     * {@link org.springframework.dao.DataIntegrityViolationException} at the
     * service layer because it never asks Hibernate to translate a JDBC
     * exception we'd then have to second-guess.
     *
     * <p>Returns the number of rows actually inserted (1 on first insert,
     * 0 when the unique constraint short-circuits the write). Either way the
     * caller re-reads via {@link #findByRoomIdAndUserId} to obtain the
     * winning row.
     */
    @Modifying
    @Query(value = """
            insert into survival_state (room_id, user_id, status, grace_ends_at)
            values (:roomId, :userId, 'ACTIVE', :graceEndsAt)
            on conflict (room_id, user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("roomId") long roomId,
            @Param("userId") long userId,
            @Param("graceEndsAt") Instant graceEndsAt);
}
