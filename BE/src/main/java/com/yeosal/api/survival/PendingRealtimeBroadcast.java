package com.yeosal.api.survival;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Delayed-emit row (V11 step 12, Architecture §4.14). RED transitions queue
 * one row here from the {@code AFTER_COMMIT} listener; a 1-minute dispatcher
 * drains matured rows and emits to {@code /topic/rooms/{roomId}/survival}.
 *
 * <p>The {@code payload} JSONB stores the unwrapped event fields — the
 * dispatcher reads {@code roomId} + {@code eventKind} + {@code userId} +
 * {@code toStatus} + {@code occurredAt} and routes by {@code roomId}.
 *
 * <p>Write-once, read-once: the dispatcher marks {@code emitted_at} only on
 * successful publish, so a broker hiccup leaves the row eligible for the
 * next tick.
 */
@Entity
@Table(name = "pending_realtime_broadcasts")
public class PendingRealtimeBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "emitted_at")
    private Instant emittedAt;

    protected PendingRealtimeBroadcast() {}

    public PendingRealtimeBroadcast(Instant scheduledAt, JsonNode payload) {
        this.scheduledAt = scheduledAt;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Instant getScheduledAt() { return scheduledAt; }
    public JsonNode getPayload() { return payload; }
    public Instant getEmittedAt() { return emittedAt; }
}
