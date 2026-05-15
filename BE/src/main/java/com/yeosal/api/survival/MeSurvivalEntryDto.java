package com.yeosal.api.survival;

/**
 * Wire shape for {@code GET /api/v1/me/survival} — cross-room aggregation of
 * the current user's own survival state (Architecture §6.4, Epic 1 retro T4).
 *
 * <p>One row per room the user is a member of. The user is always self for
 * every row, so the AC3 RED-cooldown mask never applies; status is reported
 * as the real value and the cross-room helper {@code isSpectatorAcrossAllRooms}
 * can read it directly.
 *
 * <p>{@code roomName} is included so the FE Home-tab can render the room
 * label next to the per-room badge without a second round-trip to the rooms
 * listing endpoint.
 */
public record MeSurvivalEntryDto(long roomId, String roomName, SurvivalStatus status) {}
