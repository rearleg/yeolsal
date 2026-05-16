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
     * Story 2.2 — count state transitions (YELLOW / RED / SPECTATOR shifts)
     * in a room within a half-open window {@code [fromInclusive, toExclusive)}.
     * The explicit {@code GreaterThanEqualAnd...LessThan} keywords emit
     * {@code >= AND <} — required because Spring Data's {@code Between} is
     * inclusive on both ends, which would double-count a row at a day-boundary
     * instant in adjacent digest runs (Story 2.2 review finding #1). Powers
     * the spectator daily-digest aggregator. {@code last_state_change_at} is
     * NOT NULL per V11 and indexed for the daily evaluator's room scans,
     * which this query reuses.
     */
    long countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(
            long roomId, Instant fromInclusive, Instant toExclusive);

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
     * Story 2.1 AC7 — read the {@code room_point_pool.total} counter cache
     * for a single room. V11 backfilled one row per room with total=0, so
     * the query is expected to always return a value for any valid roomId,
     * but callers MUST coalesce {@code null} → 0 defensively. No Java
     * entity / repository for {@code room_point_pool} yet — Story 4.1 will
     * lift this into proper JPA mapping; the native query here is the
     * smallest possible diff.
     */
    @Query(value = "SELECT total FROM room_point_pool WHERE room_id = :roomId",
            nativeQuery = true)
    Integer findRoomPointPoolTotal(@Param("roomId") long roomId);

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
