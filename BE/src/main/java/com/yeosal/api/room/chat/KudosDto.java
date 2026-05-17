package com.yeosal.api.room.chat;

import java.time.Instant;

/**
 * Story 3.5 — wire response for POST /api/v1/rooms/{id}/kudos.
 *
 * <p>Wrapped in {@code ApiResponse.of(KudosDto)} by the controller, so the
 * HTTP envelope is {@code {"data": {kudosId, roomId, senderUserId,
 * targetUserId, message, occurredAt}}}. The donor's FE (Story 3.2 Friend
 * Gift Modal) reads {@code kudosId} to anchor an optimistic toast; the
 * receiver's chat list discovers the row via the realtime frame /
 * {@code qk.roomMessages} cache invalidation (Story 3.5 AC9).
 *
 * <p>{@code message} is the trimmed donor input, possibly empty (never
 * null). {@code occurredAt} is ISO-8601 UTC.
 */
public record KudosDto(
        long kudosId,
        long roomId,
        long senderUserId,
        long targetUserId,
        String message,
        Instant occurredAt) {}
