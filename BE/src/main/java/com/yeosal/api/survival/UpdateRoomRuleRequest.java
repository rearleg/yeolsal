package com.yeosal.api.survival;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PATCH /api/v1/rooms/{id}/rule}. The {@code preset}
 * whitelist is enforced both here (first line of defense via Bean Validation
 * driven 400 VALIDATION) and again in the service layer (chokepoint for
 * direct test/admin callers that bypass Bean Validation).
 *
 * <p>{@code weekendInclude} is boxed ({@link Boolean}) so Jackson can tell
 * "field omitted" apart from "explicit {@code false}" — the {@code @NotNull}
 * catches the omitted case at validation time.
 */
public record UpdateRoomRuleRequest(
        @NotNull
        @Pattern(regexp = "^DAILY_UPDATE$",
                message = "preset은 DAILY_UPDATE만 허용됩니다.")
        String preset,
        @NotNull Boolean weekendInclude
) {}
