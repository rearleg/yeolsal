package com.yeosal.api.revival;

import java.util.List;

/**
 * Story 3.3 AC1 — per-room aggregation of eligible friend-gift targets for
 * the calling (giver) user. Powers the wallet badge surface
 * ({@code <FriendGiftBadge>}) and the multi-friend picker
 * ({@code <FriendGiftPickerSheet>}).
 *
 * <p>{@code eligibleCount} is redundant with {@code friends.size()} but
 * the BE writes both so the FE can early-return without iterating the
 * list. The list itself is bounded by room membership (typical 5-14
 * entries — V1 cap is 14 per Architecture §4.1), so no pagination is
 * needed in v1.
 *
 * <p>Privacy invariant (AC1 + Story 3.2 AC9) — the endpoint is scoped to
 * the caller; this DTO never reaches a third party. The
 * {@code friends.*.nickname} disclosure is therefore receiver-side only
 * (giver = caller).
 */
public record FriendGiftTargetSummaryDto(
        long roomId,
        String roomName,
        int eligibleCount,
        List<EligibleFriendDto> friends) {}
