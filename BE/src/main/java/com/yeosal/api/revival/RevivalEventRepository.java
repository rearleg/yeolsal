package com.yeosal.api.revival;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link RevivalEvent}. The two writers this story
 * cares about are:
 *
 * <ul>
 *   <li>{@link #insertOnConflictDoNothing} — secondary exactly-once defence
 *       per Architecture §4.4. The partial unique index
 *       {@code ux_revival_events_one_per_elimination (room_id, user_id,
 *       eliminated_at) WHERE succeeded = true} (V11 lines 76-78) is the
 *       backstop when the Postgres advisory lock somehow lets a duplicate
 *       through. Returns the row count (1 on insert, 0 on conflict); the
 *       service then re-reads the persisted row via
 *       {@link #findByRoomIdAndUserIdAndEliminatedAt} to obtain the new id
 *       (one extra SELECT — negligible vs. the wins of avoiding Spring
 *       Data JPA's {@code @Modifying} vs. {@code RETURNING} ambiguity).</li>
 *   <li>{@link #findByRoomIdAndUserIdAndEliminatedAt} — used by the
 *       service to retrieve the just-inserted row id (FK target for the
 *       ledger row and source for the response DTO) and by the concurrency
 *       IT to assert "exactly one successful row" after a race.</li>
 * </ul>
 *
 * <p>The advisory-lock contract requires the index column tuple to match
 * the lock key. V11 keys the index on the raw {@code eliminated_at}
 * timestamp (not a date cast) — Postgres rejected the original
 * {@code ((eliminated_at)::date)} expression with SQLSTATE 42P17 because
 * the cast is STABLE rather than IMMUTABLE (cf. V11 migration lines 52-62
 * and PR #57 / commit 4f741ff). Service writers MUST hash the millis form
 * of the timestamp into the advisory-lock key.
 */
public interface RevivalEventRepository extends JpaRepository<RevivalEvent, Long> {

    /**
     * Native {@code INSERT ... ON CONFLICT DO NOTHING} against the partial
     * unique index inference target. {@code where succeeded = true} matches
     * the index predicate exactly — required because Postgres rejects the
     * inference clause without it for partial unique indexes.
     *
     * <p>Returns {@code 1} when the row was inserted, {@code 0} when a
     * parallel attempt already won the index race. The caller MUST run this
     * inside the same {@code @Transactional} boundary that holds the
     * advisory lock — the index is only the secondary defence.
     */
    @Modifying
    @Query(
            value = """
                    insert into revival_events (
                        room_id, user_id, giver_user_id, source, source_subtype,
                        points_spent, pool_after, eliminated_at, succeeded, occurred_at
                    ) values (
                        :roomId, :userId, :giverUserId, :source, :sourceSubtype,
                        :pointsSpent, :poolAfter, :eliminatedAt, true, :occurredAt
                    )
                    on conflict (room_id, user_id, eliminated_at)
                        where succeeded = true
                        do nothing
                    """,
            nativeQuery = true)
    int insertOnConflictDoNothing(
            @Param("roomId") long roomId,
            @Param("userId") long userId,
            @Param("giverUserId") Long giverUserId,
            @Param("source") String source,
            @Param("sourceSubtype") String sourceSubtype,
            @Param("pointsSpent") short pointsSpent,
            @Param("poolAfter") int poolAfter,
            @Param("eliminatedAt") Instant eliminatedAt,
            @Param("occurredAt") Instant occurredAt);

    /**
     * Concurrency-IT + service-path finder. Returns at most one row because
     * the partial unique index makes {@code (room_id, user_id,
     * eliminated_at)} unique among {@code succeeded = true} rows. Story 3.1
     * never writes {@code succeeded = false} rows, so the absence of a
     * loser-row is part of the contract.
     */
    Optional<RevivalEvent> findByRoomIdAndUserIdAndEliminatedAt(
            long roomId, long userId, Instant eliminatedAt);
}
