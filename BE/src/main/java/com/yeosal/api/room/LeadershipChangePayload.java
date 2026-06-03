package com.yeosal.api.room;

/**
 * Story 5.2 — realtime payload broadcast on
 * {@code /topic/rooms.{roomId}.survival} when a leader transfer commits.
 * The {@code reason} field discriminates Story 5.2's manual flow
 * ({@code "MANUAL_TRANSFER"}) from Story 5.3's auto-promotion flow
 * ({@code "AUTO_ELIMINATION"}, reserved).
 */
public record LeadershipChangePayload(
        long roomId,
        long previousLeaderUserId,
        long newLeaderUserId,
        String reason
) {}
