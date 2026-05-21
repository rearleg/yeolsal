package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Story 3.3 AC1 — single eligible target friend inside a room. Component of
 * {@link FriendGiftTargetSummaryDto#friends()}. The picker sheet
 * (FE {@code FriendGiftPickerSheet}) renders one row per entry.
 *
 * <p>{@code status} is the raw {@code survival_state.status} value — the
 * picker uses it as a UX hint ("RED" / "SPECTATOR") without exposing finer
 * lifecycle details. {@code eliminatedAt} carries the partial-unique-index
 * key argument so the FE could compute the same lock-key fingerprint the
 * BE uses for advisory-lock dedup (informational; not used for write).
 */
public record EligibleFriendDto(
        long userId,
        String nickname,
        String status,
        Instant eliminatedAt) {}
