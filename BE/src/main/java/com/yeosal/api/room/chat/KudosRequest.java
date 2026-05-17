package com.yeosal.api.room.chat;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Story 3.5 — POST /api/v1/rooms/{id}/kudos request body.
 *
 * <p>{@code targetUserId} is required; {@code message} is optional per
 * epics 578 ({@code message?: string}). Bean Validation treats {@code
 * null} as valid against {@code @Size}, so the only enforcement on the
 * message field is the 60-char upper bound when a value is present.
 */
public record KudosRequest(
        @NotNull Long targetUserId,
        @Size(max = 60) String message) {}
