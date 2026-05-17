package com.yeosal.api.room.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * Cursor-based descending fetch. Pass {@code Long.MAX_VALUE} for the
     * first page so the caller doesn't have to branch on null. The
     * {@code (room_id, id desc)} index from V7 makes this an index-only
     * range scan.
     */
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(
            Long roomId, Long beforeId, Pageable pageable);

    /**
     * Story 2.2 — count chat messages in a room within a half-open window
     * {@code [fromInclusive, toExclusive)}. The explicit
     * {@code GreaterThanEqualAnd...LessThan} keywords emit {@code >= AND <}
     * — required because Spring Data's {@code Between} is inclusive on both
     * ends, which would double-count a row landing exactly on a day-boundary
     * instant in adjacent digest runs (Story 2.2 review finding #1).
     * Powers the spectator daily-digest aggregator; the {@code (room_id, id desc)}
     * index from V7 also covers this range-scan because {@code created_at} is
     * monotonic with {@code id} in append-only chat semantics.
     */
    long countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long roomId, Instant fromInclusive, Instant toExclusive);

    /**
     * Idempotency check for the MILESTONE publish hook. Returns true if
     * we have already announced this user's monthly threshold hit in
     * {@code roomId} for the given {@code "YYYY-MM"} month tag. The
     * actor identity is carried in {@code payload->>'userId'} (system
     * rows have a {@code NULL} {@code sender_user_id} by design), and
     * the month tag in {@code payload->>'month'}. A retry / second
     * reflection submission in the same calendar month is a no-op;
     * a cron retrying for the same room won't re-announce either.
     * Native query because we read JSONB keys the JPA query language
     * can't address.
     */
    @Query(value = """
            select exists(
                select 1 from chat_messages
                where room_id = :roomId
                  and kind = 'MILESTONE'
                  and payload->>'userId' = :userId
                  and payload->>'month' = :month
            )
            """, nativeQuery = true)
    boolean existsMilestoneForMonth(
            @Param("roomId") Long roomId,
            @Param("userId") String userId,
            @Param("month") String month);

    /**
     * Atomic once-per-(room, user, date) MILESTONE insert. The V9
     * partial unique expression index over
     * {@code (room_id, payload->>'userId', payload->>'date') WHERE kind = 'MILESTONE'}
     * is the source of truth — concurrent reflections, retried
     * afterCommit callbacks, and a duplicate same-day publish all
     * converge to a single row. Returns the rows actually inserted
     * (0 or 1).
     *
     * <p>The product semantic shifted from "once a month at the
     * threshold" (V8) to "once a day per (goal+reflection) entry"
     * (V9), so the dedup tuple followed.
     *
     * <p>{@code payload} is a literal JSON object as a String; it is
     * cast to {@code jsonb} so JSONB-side validation rejects malformed
     * input the same way Hibernate's JsonNode column would.
     */
    @Modifying
    @Query(value = """
            insert into chat_messages (room_id, sender_user_id, kind, body, payload)
            values (:roomId, null, 'MILESTONE', :body, cast(:payload as jsonb))
            on conflict (
                room_id,
                ((payload ->> 'userId')),
                ((payload ->> 'date'))
            ) where kind = 'MILESTONE'
            do nothing
            """, nativeQuery = true)
    int insertMilestoneIfAbsent(
            @Param("roomId") Long roomId,
            @Param("body") String body,
            @Param("payload") String payload);

    /**
     * Story 3.5 — atomic at-most-one kudos per (sender, target, KST date)
     * via the V12 partial unique index {@code ux_kudos_one_per_day}. The
     * {@code on conflict} predicate {@code where kind = 'KUDOS'} MUST
     * match the index predicate exactly so the conflict path is invoked
     * (a mismatched predicate falls back to a generic 23505 outside the
     * named constraint, which would defeat the typed-exception
     * translation in {@link KudosService}).
     *
     * <p>{@code payload} is a literal JSON object as a String (the same
     * shape V8/V9 milestone-dedup uses): cast to {@code jsonb} so JSONB
     * validates it server-side. Both {@code senderUserId} and {@code
     * targetUserId} are stored as JSON strings — the {@code text->>}
     * operator returns text regardless of whether the writer used a JSON
     * string or number, but storing as strings keeps the index stable
     * against future numeric writers.
     *
     * <p>Returns rows actually inserted (0 on same-day duplicate; 1 on
     * insert). Mirrors {@link #insertMilestoneIfAbsent}.
     */
    @Modifying
    @Query(value = """
            insert into chat_messages (room_id, sender_user_id, kind, body, payload)
            values (:roomId, :senderUserId, 'KUDOS', :body, cast(:payload as jsonb))
            on conflict (
                sender_user_id,
                ((payload ->> 'targetUserId')),
                (((created_at at time zone 'Asia/Seoul')::date))
            ) where kind = 'KUDOS'
            do nothing
            """, nativeQuery = true)
    int insertKudosIfAbsent(
            @Param("roomId") Long roomId,
            @Param("senderUserId") Long senderUserId,
            @Param("body") String body,
            @Param("payload") String payload);

    /**
     * Story 3.5 — read the just-inserted kudos row id so {@code KudosService}
     * can return a stable {@code KudosDto.kudosId} without depending on a
     * fragile {@code RETURNING id} (Spring Data's {@code @Modifying} INSERT
     * returns only the row count). Mirrors the Story 3.1
     * {@code RevivalEventRepository.findByRoomIdAndUserIdAndEliminatedAt}
     * post-insert read pattern.
     *
     * <p>{@code kstDate} is computed in Java via
     * {@code LocalDate.now(ZoneId.of("Asia/Seoul"))} and bound directly —
     * Postgres maps Java's {@code LocalDate} to {@code date} so the
     * comparison is type-stable with the V12 index expression. Limit 1 is
     * defence in depth; the partial unique index guarantees at most one
     * row per triple.
     */
    @Query(value = """
            select id from chat_messages
            where sender_user_id = :senderUserId
              and kind = 'KUDOS'
              and payload->>'targetUserId' = :targetUserId
              and ((created_at at time zone 'Asia/Seoul')::date) = :kstDate
            order by id desc
            limit 1
            """, nativeQuery = true)
    Optional<Long> findKudosId(
            @Param("senderUserId") Long senderUserId,
            @Param("targetUserId") String targetUserId,
            @Param("kstDate") LocalDate kstDate);
}
