package com.yeosal.api.revival;

import java.time.Instant;

/**
 * STOMP wire-format payload for the {@code POINT_POOL_CHANGE} frame on
 * {@code /topic/rooms.{roomId}.points}. Downstream consumer of the
 * in-process {@link PointPoolChangeEvent}; the listener strips
 * orchestration fields if/when future stories add them to the event
 * record.
 *
 * <p>Frame consumed by:
 * <ul>
 *   <li>Story 4.1 — {@code useRoomPoints} FE hook will subscribe to the
 *       topic. Story 3.1 only emits; no FE subscriber yet.</li>
 *   <li>Story 7.x — final-3 ceremony aggregation may listen for
 *       pool-burn / pool-distribute events on the same topic.</li>
 * </ul>
 */
public record PointPoolChangePayload(
        long roomId,
        int delta,
        int newTotal,
        long sourceRevivalEventId,
        Instant occurredAt) {}
