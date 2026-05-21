package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Story 3.2 AC2 — in-process event carrying a successful friend-gift
 * across the AFTER_COMMIT boundary (Spring
 * {@code TransactionalEventListener}). Consumed by
 * {@link FriendGiftRealtimeListener} for the receiver donor-confirmation
 * push (FR-8.3.5).
 *
 * <p>Distinct from {@link com.yeosal.api.survival.SurvivalStateTransitionEvent}
 * (which also fires for the receiver's RED → ACTIVE transition) because
 * the friend-gift listener needs the giver+receiver+revivalEventId tuple
 * to compose the push payload, and the transition event only carries the
 * receiver's identity.
 *
 * @param roomId          affected room
 * @param giverUserId     donor user id (visible to the receiver only —
 *                        FR-8.3.5)
 * @param receiverUserId  receiver user id
 * @param revivalEventId  {@code revival_events.id} just inserted — used
 *                        as the receiver-side push dedup key
 *                        ({@code "revival:{revivalEventId}"})
 * @param occurredAt      single {@code Clock.instant()} snapshot shared
 *                        with the response DTO + survival transition event
 */
public record FriendGiftSentEvent(
        long roomId,
        long giverUserId,
        long receiverUserId,
        long revivalEventId,
        Instant occurredAt) {}
