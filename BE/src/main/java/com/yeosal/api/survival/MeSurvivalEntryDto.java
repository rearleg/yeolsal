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
 *
 * <p>Story 2.1 AC7 additions ({@code personalPoints} + {@code roomPointPool})
 * power the spectator-only {@code WalletPreview} block on the Today tab.
 * Both fields default to {@code 0} when source data is missing — the FE
 * renders that as a blank value rather than an empty state.
 *
 * <ul>
 *   <li>{@code personalPoints} — running balance summed from
 *       {@code personal_points_ledger.delta} for the
 *       {@code (user_id, room_id)} pair. Includes every {@code LedgerReason}
 *       (SURVIVAL accrual, REVIVAL_SPEND, etc.).</li>
 *   <li>{@code roomPointPool} — placeholder. The {@code room_point_pool}
 *       table itself ships with Story 4.1; until then the field is always
 *       {@code 0} so the FE can render the row with a {@code TODO Story 3.4}
 *       placeholder caption rather than failing the contract.</li>
 * </ul>
 */
public record MeSurvivalEntryDto(
        long roomId,
        String roomName,
        SurvivalStatus status,
        int personalPoints,
        int roomPointPool) {}
