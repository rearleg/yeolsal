package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Story 3.4 AC3 — wire-format row for
 * {@code GET /api/v1/me/received-revivals?roomId={id}}. One entry per
 * {@code revival_events} row where {@code user_id = me AND room_id = R AND
 * succeeded = true}, covering all 3 sources (FREE_TICKET, PERSONAL_POINTS,
 * FRIEND_GIFT). Chronological DESC order (most recent first).
 *
 * <p>{@code donorUserId} and {@code donorNickname} are populated only for
 * FRIEND_GIFT rows per FR-8.3.5 (receiver-only donor visibility). The
 * endpoint that produces this DTO MUST NEVER include rows where the calling
 * user is {@code giver_user_id}; that is the donor's wallet history, a
 * separate v1.5 surface (UX line 1334).
 *
 * @param revivalEventId   {@code revival_events.id} primary key
 * @param roomId           room the revival happened in (always equal to the
 *                         request's {@code ?roomId=} query parameter)
 * @param roomName         room display name (resolved server-side)
 * @param source           FREE_TICKET | PERSONAL_POINTS | FRIEND_GIFT
 * @param donorUserId      giver's user id; null for FREE_TICKET and
 *                         PERSONAL_POINTS
 * @param donorNickname    giver's display nickname; null for FREE_TICKET and
 *                         PERSONAL_POINTS
 * @param occurredAt       ISO-8601 UTC instant the revival succeeded
 */
public record ReceivedRevivalDto(
        long revivalEventId,
        long roomId,
        String roomName,
        String source,
        Long donorUserId,
        String donorNickname,
        Instant occurredAt) {}
