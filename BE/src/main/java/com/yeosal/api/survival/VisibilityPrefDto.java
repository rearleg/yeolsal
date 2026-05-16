package com.yeosal.api.survival;

import java.time.Instant;

/**
 * Wire shape for {@code GET /api/v1/me/visibility-prefs} and the response
 * envelope of {@code POST /api/v1/me/visibility-prefs} (Story 2.3 AC2).
 * One entry per room the authenticated user is a member of — rooms without
 * an explicit row are materialized server-side with
 * {@code shareOnElimination = false} and {@code updatedAt = null}.
 */
public record VisibilityPrefDto(
        long roomId,
        String roomName,
        boolean shareOnElimination,
        Instant updatedAt) {}
