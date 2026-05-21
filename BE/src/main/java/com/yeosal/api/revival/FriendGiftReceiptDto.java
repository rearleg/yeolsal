package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Story 3.2 AC7 — wire-format row for {@code GET /api/v1/me/friend-gift-receipts}.
 * One entry per FRIEND_GIFT revival the calling user received within the
 * last 7 KST days (window 0..6 inclusive — see CRITICAL note 5 in the
 * story file).
 *
 * @param revivalEventId   the {@code revival_events.id} for this receipt
 * @param roomId           the room in which the revival happened
 * @param roomName         room display name (resolved server-side)
 * @param donorUserId      the giver's user id (visible to the receiver
 *                         only per FR-8.3.5; AC9 privacy)
 * @param donorNickname    the giver's display nickname
 * @param occurredAt       ISO-8601 UTC instant the revival succeeded
 * @param daysSinceRevival KST day count: 0 on the elimination day,
 *                         increases by 1 each KST midnight, stops at 6
 *                         (7-day-old rows are filtered out by the query
 *                         itself)
 */
public record FriendGiftReceiptDto(
        long revivalEventId,
        long roomId,
        String roomName,
        long donorUserId,
        String donorNickname,
        Instant occurredAt,
        int daysSinceRevival) {}
