package com.yeosal.api.revival;

import java.time.Instant;

/**
 * In-process event carrying a {@code room_point_pool} mutation across the
 * AFTER_COMMIT boundary (Spring {@code TransactionalEventListener}). The
 * revival service publishes this from inside its {@code @Transactional}
 * method; the {@link RoomPointPoolRealtimeListener} fires only after the
 * surrounding transaction commits, so a rollback never lights up the
 * realtime fan-out.
 *
 * <p>Distinct from {@link PointPoolChangePayload} (the wire shape) because
 * future stories (Story 4.1 pool counter cache, Story 7.1 final-3 ceremony)
 * may carry additional orchestration fields on the in-process side that
 * shouldn't leak onto the STOMP topic.
 *
 * @param roomId                affected room
 * @param delta                 signed change to {@code total} (positive
 *                              for revivals)
 * @param newTotal              {@code total} after the UPDATE — same
 *                              value used in the response DTO's
 *                              {@code roomPointPoolAfter} field
 * @param sourceRevivalEventId  the {@code revival_events.id} that caused
 *                              the mutation
 * @param occurredAt            single {@code Clock.instant()} snapshot
 *                              shared with the survival transition event
 */
public record PointPoolChangeEvent(
        long roomId,
        int delta,
        int newTotal,
        long sourceRevivalEventId,
        Instant occurredAt) {}
