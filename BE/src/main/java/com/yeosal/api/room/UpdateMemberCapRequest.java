package com.yeosal.api.room;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Story 5.2 — request body for {@code PATCH /api/v1/rooms/{id}/members/cap}.
 * Validation contract mirrors {@code RoomController.CreateRoomRequest.maxMembers}
 * ({@code @Min(2) @Max(30)}) byte-for-byte so the FE picker contract stays
 * single-source.
 */
public record UpdateMemberCapRequest(
        @NotNull
        @Min(value = 2, message = "정원은 2 이상이어야 합니다.")
        @Max(value = 30, message = "정원은 30 이하여야 합니다.")
        Integer maxMembers
) {}
