package com.yeosal.api.survival;

import java.time.Instant;

/**
 * STOMP frame payload for survival-state transitions (Story 1.2 AC5, AC7).
 *
 * <p>Used on both channels:
 * <ul>
 *   <li>{@code /user/{userId}/queue/private-survival} — immediate, private,
 *       owner + affected user.</li>
 *   <li>{@code /topic/rooms.{roomId}.survival} — immediate for non-RED,
 *       delayed-by-24h for RED (via {@code pending_realtime_broadcasts}).</li>
 * </ul>
 *
 * <p>{@code eliminatedAt} and {@code broadVisibilityAt} are both {@code null}
 * for non-RED transitions — YELLOW is not privacy-gated and is never an
 * elimination. For RED transitions {@code eliminatedAt == occurredAt} (same
 * {@code clock.instant()} snapshot per AC9), but the field is surfaced
 * explicitly so the FE can branch on the named RED-specific key (AC7).
 */
public record SurvivalStateChangePayload(
        long roomId,
        long userId,
        SurvivalStatus fromStatus,
        SurvivalStatus toStatus,
        Instant occurredAt,
        Instant eliminatedAt,
        Instant broadVisibilityAt) {}
