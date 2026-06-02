package com.yeosal.api.survival;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Month-keyed rule history per room. Existing rooms are covered by the
 * migration backfill and new rooms seed a default row during creation, so the
 * evaluator can treat a missing row as a data-shape bug rather than a
 * user-facing error.
 *
 * <p>{@code rule_payload} is JSONB; mapped here as a Jackson {@code JsonNode}
 * to stay consistent with {@code ChatMessage.payload} and to keep
 * {@code ddl-auto: validate} happy (raw {@code String} mapping fails column
 * type validation against Postgres {@code jsonb}). Shape:
 * <pre>{@code { "preset": "DAILY_UPDATE", "weekendInclude": true | false }}</pre>
 */
@Entity
@Table(name = "room_rule_versions")
public class RoomRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "effective_from_month", nullable = false, length = 7)
    private String effectiveFromMonth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode rulePayload;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoomRuleVersion() {}

    public RoomRuleVersion(
            long roomId, String effectiveFromMonth, JsonNode rulePayload, long createdByUserId) {
        this.roomId = roomId;
        this.effectiveFromMonth = effectiveFromMonth;
        this.rulePayload = rulePayload;
        this.createdByUserId = createdByUserId;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public String getEffectiveFromMonth() { return effectiveFromMonth; }
    public JsonNode getRulePayload() { return rulePayload; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
