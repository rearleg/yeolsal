package com.yeosal.api.revival;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only point movements per {@code (user, room)} pair (V11 step 6).
 * Story 1.2 ships the FIRST file in {@code revival/} — Stories 3.x own the
 * rest of the module (revival flows, friend gifts, room pool).
 *
 * <p>{@code user_id} and {@code room_id} are stored as scalar columns (no
 * JPA {@code @ManyToOne}) so the evaluator never has to resolve a managed
 * {@code User} / {@code Room} just to write a row. The SURVIVAL write path
 * loops once per compliant member; scalar mapping keeps that loop cheap.
 *
 * <p>Idempotency for SURVIVAL writes is NOT enforced at the ledger level —
 * the {@code notification_log (user_id, kind='SURVIVAL_STATE', key)} gate
 * upstream in {@code SurvivalStateService#evaluateRoom} short-circuits the
 * ledger insert when the daily run has already processed a user.
 */
@Entity
@Table(name = "personal_points_ledger")
public class PersonalPointsLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "delta", nullable = false)
    private short delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 24)
    private LedgerReason reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "revival_event_id")
    private Long revivalEventId;

    protected PersonalPointsLedger() {}

    public PersonalPointsLedger(
            long userId, long roomId, short delta, LedgerReason reason, Instant occurredAt) {
        this.userId = userId;
        this.roomId = roomId;
        this.delta = delta;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public PersonalPointsLedger(
            long userId,
            long roomId,
            short delta,
            LedgerReason reason,
            Instant occurredAt,
            Long revivalEventId) {
        this(userId, roomId, delta, reason, occurredAt);
        this.revivalEventId = revivalEventId;
    }

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getRoomId() { return roomId; }
    public short getDelta() { return delta; }
    public LedgerReason getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getRevivalEventId() { return revivalEventId; }
}
