package com.yeosal.api.revival;

/**
 * Reasons admitted by the {@code personal_points_ledger.reason} CHECK
 * constraint (V11 step 6). Mapped with {@code @Enumerated(EnumType.STRING)}
 * everywhere — never {@code ORDINAL} — so future reordering or insertion of
 * values stays forward-compatible with stored rows.
 *
 * <p>Story 1.2 writes only {@link #SURVIVAL}; the remaining reasons land in
 * Stories 3.x (revival economy) and beyond.
 */
public enum LedgerReason {
    SURVIVAL,
    REVIVAL_SPEND,
    FRIEND_GIFT_SPEND,
    ROOM_LEAVE,
    ADJUSTMENT
}
