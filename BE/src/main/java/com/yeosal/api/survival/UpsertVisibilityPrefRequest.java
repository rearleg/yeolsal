package com.yeosal.api.survival;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/me/visibility-prefs} (Story 2.3 AC2).
 * Boxed types so {@code @NotNull} can guard a missing-field 400 VALIDATION
 * before the service is reached.
 */
public record UpsertVisibilityPrefRequest(
        @NotNull Long roomId,
        @NotNull Boolean shareOnElimination) {}
