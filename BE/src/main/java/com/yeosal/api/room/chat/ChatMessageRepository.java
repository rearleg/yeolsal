package com.yeosal.api.room.chat;

import java.util.List;
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
}
