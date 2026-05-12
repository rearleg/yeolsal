package com.yeosal.api.survival;

import java.time.Instant;

/**
 * Wire shape for {@code GET /api/v1/rooms/{id}/survival} (Story 1.3 AC9).
 *
 * <p>Privacy gate (AC3, applied by {@link SurvivalStateService#roster}): when a
 * RED row is still in the 24h cooldown and the viewer is neither the leader
 * nor the affected user, {@link #status} is reported as
 * {@link SurvivalStatus#ACTIVE} and {@link #lastStateChangeAt},
 * {@link #eliminatedAt}, {@link #broadVisibilityAt} are all {@code null}.
 * Architecture §4.14 "Never trust the FE to filter privacy" — the rule
 * applies to every leakable field, not just {@code status}.
 *
 * <p>{@code roomId} is intentionally absent — it lives in the request path,
 * so emitting it here is bandwidth waste (AC9).
 */
public record SurvivalStateDto(
        long userId,
        String nickname,
        SurvivalStatus status,
        Instant lastStateChangeAt,
        Instant eliminatedAt,
        Instant broadVisibilityAt) {}
