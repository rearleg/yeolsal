package com.yeosal.api.revival;

import jakarta.validation.constraints.NotNull;

/**
 * Wire-format request body for {@code POST /api/v1/rooms/{id}/revivals/gifts}
 * (Story 3.2 AC1, FR-8.3.3 + Story 3.3 AC3).
 *
 * <p>The giver's identity comes from {@code CurrentUser.require(auth)},
 * the source is implicitly {@link RevivalSource#FRIEND_GIFT}, and the
 * 5-point cost is fixed in service code.
 *
 * <p>{@code sourceSubtype} is optional (nullable). Jackson maps a missing
 * JSON field to {@code null}; the service layer defaults {@code null} to
 * {@link RevivalSourceSubtype#PUSH_INITIATED} for backward compat with
 * Story 3.2 callers that don't send the field. An invalid enum literal in
 * the JSON body surfaces as {@link
 * org.springframework.http.converter.HttpMessageNotReadableException} →
 * 400 VALIDATION via the global handler.
 */
public record FriendGiftRequest(
        @NotNull Long targetUserId,
        RevivalSourceSubtype sourceSubtype) {}
