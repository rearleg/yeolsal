package com.yeosal.api.revival;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * Story 3.2 AC1 step 17 — lifetime-1 M3.5 marker pre-check. Returns
     * {@code true} iff the giver has at least one prior FRIEND_GIFT row
     * in {@code revival_events} EXCLUDING the row whose id is
     * {@code excludeRevivalEventId}.
     *
     * <p>The EXCLUDING-self semantics is what makes the lifetime-1 check
     * race-free inside the friend-gift transaction: the freshly-inserted
     * row IS visible to a SELECT inside the same transaction (PostgreSQL
     * sees its own writes), so the caller must pass the just-inserted row
     * id to exclude itself from the "any prior gift" question.
     *
     * <p>Pass {@code -1L} as {@code excludeRevivalEventId} from any caller
     * that does NOT need to exclude a row (e.g. the
     * {@code GET /me/has-given-friend-gift} endpoint at BE-12, where the
     * answer is purely retrospective). No row has id {@code -1L}, so the
     * exclude clause becomes a no-op.
     *
     * <p>Native query (not JPQL) so the cross-package call from
     * {@code RevivalService.reviveFriend} reads as the precise SQL we
     * intend, with no Hibernate dialect quirks around the {@code id <>}
     * comparison on an autoincrement column.
     */
    @Query(value = """
            select exists(
                select 1 from revival_events
                where giver_user_id = :giverId
                  and source = 'FRIEND_GIFT'
                  and succeeded = true
                  and id <> :excludeId
            )
            """, nativeQuery = true)
    boolean existsFriendGiftSendByGiver(
            @Param("giverId") long giverUserId,
            @Param("excludeId") long excludeRevivalEventId);

    /**
     * Story 3.2 AC7 — 7-day FRIEND_GIFT receive window for the receiver
     * (epics 501-503, UX line 1334). The KST day-level predicate uses the
     * IMMUTABLE {@code (occurred_at at time zone 'Asia/Seoul')::date} cast
     * shape consistent with the V12 partial-unique-index expression Story
     * 3.5 shipped (PR #57 precedent — the cast is required by partial-
     * unique-index expressions and applied here for codebase consistency
     * even though the SELECT WHERE clause does not strictly need
     * IMMUTABLE).
     *
     * <p>Caller (the {@code MeFriendGiftController}) passes
     * {@code kstToday = LocalDate.now(ZoneId.of("Asia/Seoul"))} and
     * {@code kstWindowStart = kstToday.minusDays(6)} for inclusive 0..6 day
     * coverage. A 7-day-old row falls outside the {@code >=} window and is
     * naturally excluded (story CRITICAL note 5).
     *
     * <p>Filters {@code user_id = :receiverUserId} — the receiver is the
     * {@code user_id} column for FRIEND_GIFT rows; {@code giver_user_id} is
     * the donor. AC9 privacy requires this query NEVER return rows where
     * the calling user is the {@code giver_user_id} (the donor's wallet
     * history is a separate surface owned by Story 3.4).
     */
    @Query(value = """
            select * from revival_events
            where user_id = :receiverUserId
              and source = 'FRIEND_GIFT'
              and succeeded = true
              and ((occurred_at at time zone 'Asia/Seoul')::date) >= :kstWindowStart
              and ((occurred_at at time zone 'Asia/Seoul')::date) <= :kstToday
            order by occurred_at desc
            """, nativeQuery = true)
    List<RevivalEvent> findFriendGiftReceiptsWithin7Days(
            @Param("receiverUserId") long receiverUserId,
            @Param("kstWindowStart") LocalDate kstWindowStart,
            @Param("kstToday") LocalDate kstToday);

    /**
     * Story 3.4 AC3 — lifetime received-revival history for one
     * {@code (receiverUserId, roomId)} pair, covering all three sources
     * (FREE_TICKET, PERSONAL_POINTS, FRIEND_GIFT). DESC by
     * {@code occurred_at} with {@code id DESC} as tiebreaker so the
     * response is deterministic.
     *
     * <p>{@code where user_id = :receiverUserId} keys on the RECEIVER
     * column. FRIEND_GIFT rows where the caller is the donor
     * ({@code giver_user_id}) are NOT returned — that is the donor's
     * wallet history (a separate v1.5 surface per UX line 1334). AC9
     * privacy invariant from Story 3.2 carried forward.
     *
     * <p>{@code succeeded = true} filter is defensive — Story 3.1 / 3.2
     * services never write {@code succeeded = false} rows, but the
     * defence keeps the contract honest if a future writer slips.
     */
    @Query(value = """
            select * from revival_events
            where user_id = :receiverUserId
              and room_id = :roomId
              and succeeded = true
            order by occurred_at desc, id desc
            """, nativeQuery = true)
    List<RevivalEvent> findReceivedRevivalsByRoom(
            @Param("receiverUserId") long receiverUserId,
            @Param("roomId") long roomId);
}
