package com.yeosal.api.room.chat;

import java.time.Instant;

/**
 * Story 3.5 — application event published by {@link KudosService} after
 * a kudos row commits. Consumed by {@code KudosRealtimeListener} in the
 * {@code AFTER_COMMIT} phase to (a) emit the {@code
 * /topic/rooms.{roomId}.kudos} realtime frame and (b) fire the receiver
 * push notification ({@code NotificationKind.KUDOS_RECEIVED}).
 *
 * <p>Distinct from {@link KudosDto} because the event carries the
 * {@code messagePreview} (a 40-char head of the rendered chat body) for
 * the realtime payload, not the donor-input message body — and may
 * accumulate orchestration fields in future stories (e.g. retry counters)
 * without polluting the wire-facing DTO.
 */
public record KudosSentEvent(
        long roomId,
        long senderUserId,
        long targetUserId,
        String messagePreview,
        Instant occurredAt) {}
