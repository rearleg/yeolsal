package com.yeosal.api.room;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Story 5.2 — request body for
 * {@code POST /api/v1/rooms/{id}/transfer-leadership}. Boxed {@code Long}
 * (not {@code long}) so Jackson can distinguish "field absent" from
 * "explicit 0" — {@code @NotNull} catches the missing case before the
 * service ever sees it.
 */
public record TransferLeadershipRequest(
        @NotNull
        @Positive(message = "targetUserId는 양수여야 합니다.")
        Long targetUserId
) {}
