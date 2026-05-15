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
     * Fetch-join variant used by Story 1.3 AC10 roster — eagerly loads the
     * {@code user} association so the response-shaping loop ({@code
     * SurvivalStateService.roster}) can read {@code s.getUser().getId()} as
     * an index key without triggering one {@code user} SELECT per row.
     */
    @Query("""
            select s
            from SurvivalState s
            join fetch s.user
            where s.room.id = :roomId
            """)
    List<SurvivalState> findByRoomIdFetchingUser(@Param("roomId") long roomId);

    /**
     * Cross-room aggregation used by {@code GET /api/v1/me/survival}
     * (Architecture §6.4, Epic 1 retro T4). Fetch-joins {@code room} so the
     * controller can read {@code s.getRoom().getId()} / {@code .getName()}
     * outside the {@code @Transactional} boundary without triggering N+1
     * lazy loads (one room SELECT per row).
     */
    @Query("""
            select s
            from SurvivalState s
            join fetch s.room
            where s.user.id = :userId
            """)
    List<SurvivalState> findByUserIdFetchingRoom(@Param("userId") long userId);

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
