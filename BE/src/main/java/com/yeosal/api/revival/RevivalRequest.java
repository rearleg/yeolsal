package com.yeosal.api.revival;

import jakarta.validation.constraints.NotNull;

/**
 * Wire-format request body for {@code POST /api/v1/rooms/{id}/revival}
 * (FR-8.3.2, epics line 432). Jackson maps the {@code "source"} JSON field
 * to the enum; missing values produce a {@code 400 VALIDATION} via the
 * existing {@code MethodArgumentNotValidException} handler.
 *
 * <p>Story 3.1 only accepts {@link RevivalSource#FREE_TICKET} and
 * {@link RevivalSource#PERSONAL_POINTS}; the service layer rejects
 * {@link RevivalSource#FRIEND_GIFT} (Story 3.2 introduces a separate
 * friend-gift endpoint). The enum carries all three values to keep the
 * persistence contract aligned across the epic.
 */
public record RevivalRequest(@NotNull RevivalSource source) {}
