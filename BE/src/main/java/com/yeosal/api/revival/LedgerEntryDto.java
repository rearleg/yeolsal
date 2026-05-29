package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Story 3.4 AC2 — wire-format row for
 * {@code GET /api/v1/me/personal-points-ledger?roomId={id}}. One entry per
 * {@code personal_points_ledger} row in chronological DESC order (most recent
 * first). The headline balance is the SUM of {@code delta} across the list
 * (BE-authoritative — the FE renders the value, never recomputes).
 *
 * @param id              {@code personal_points_ledger.id} primary key
 * @param roomId          room this delta applies to (always equal to the
 *                        request's {@code ?roomId=} query parameter)
 * @param delta           signed point movement (positive = earn, negative =
 *                        spend)
 * @param reason          one of SURVIVAL / REVIVAL_SPEND / FRIEND_GIFT_SPEND
 *                        / ROOM_LEAVE / ADJUSTMENT (see {@link LedgerReason})
 * @param occurredAt      ISO-8601 UTC instant the row was appended
 * @param revivalEventId  FK to {@code revival_events.id}; nullable, set only
 *                        for {@code REVIVAL_SPEND} and {@code FRIEND_GIFT_SPEND}
 *                        rows so the FE can link the ledger entry back to its
 *                        revival event
 */
public record LedgerEntryDto(
        long id,
        long roomId,
        short delta,
        String reason,
        Instant occurredAt,
        Long revivalEventId) {}
