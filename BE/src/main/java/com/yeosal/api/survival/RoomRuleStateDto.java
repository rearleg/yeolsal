package com.yeosal.api.survival;

/**
 * Wire-contract DTO for the GET endpoint at {@code /api/v1/rooms/{id}/rule}.
 * {@code current} is the rule effective for the current calendar month and
 * is never {@code null}: migration backfill covers existing rooms and room
 * creation seeds new ones. {@code pending} carries any leader-staged edit for
 * the next calendar month, or {@code null} when no future row exists.
 */
public record RoomRuleStateDto(
        RoomRuleVersionDto current,
        RoomRuleVersionDto pending
) {}
