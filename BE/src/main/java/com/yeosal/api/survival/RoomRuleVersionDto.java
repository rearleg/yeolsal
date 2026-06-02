package com.yeosal.api.survival;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Wire-contract DTO for a single {@link RoomRuleVersion} row. The {@code
 * preset} and {@code weekendInclude} fields are unpacked from the JSONB
 * {@code rule_payload} so the FE never parses the raw JsonNode shape.
 * Field names are serialized verbatim by Jackson, so they form part of the
 * client wire contract.
 */
public record RoomRuleVersionDto(
        long id,
        String preset,
        boolean weekendInclude,
        String effectiveFromMonth,
        long createdByUserId,
        Instant createdAt
) {

    public static RoomRuleVersionDto from(RoomRuleVersion row) {
        JsonNode payload = row.getRulePayload();
        String preset = payload.path("preset").asText("DAILY_UPDATE");
        boolean weekendInclude = payload.path("weekendInclude").asBoolean(true);
        return new RoomRuleVersionDto(
                row.getId(),
                preset,
                weekendInclude,
                row.getEffectiveFromMonth(),
                row.getCreatedByUserId(),
                row.getCreatedAt());
    }
}
