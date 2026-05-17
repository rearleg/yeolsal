package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Wire-format response payload for {@code POST /api/v1/rooms/{id}/revival}
 * (Story 3.1 AC2/AC3). The FE confirm-modal flow reads
 * {@code roomPointPoolAfter} for the post-revival toast / wallet refresh
 * and {@code source} for the success-copy branch.
 *
 * <p>{@code source} is serialized as a String (the enum's
 * {@link RevivalSource#name()}) so the wire contract stays stable even if
 * the Java enum gains new ordinal positions later (Story 3.2 introduces
 * FRIEND_GIFT).
 */
public record RevivalEventDto(
        long revivalEventId,
        String source,
        int pointsSpent,
        int roomPointPoolAfter,
        Instant occurredAt) {}
