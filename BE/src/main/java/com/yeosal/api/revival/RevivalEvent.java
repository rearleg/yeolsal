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
 * Append-only audit row for one successful (or attempted) revival per
 * elimination (V11 step 5, Architecture §4.4). Scalar {@code room_id} /
 * {@code user_id} / {@code giver_user_id} columns avoid the lazy-load cost
 * of {@code @ManyToOne} associations on the write path — the service
 * supplies primitive ids and never resolves managed entities just to insert
 * a row.
 *
 * <p>Exactly-once is defended by the partial unique index
 * {@code ux_revival_events_one_per_elimination (room_id, user_id,
 * eliminated_at) WHERE succeeded = true} (V11 lines 76-78). Service writers
 * use a native {@code INSERT ... ON CONFLICT DO NOTHING RETURNING id} — see
 * {@link RevivalEventRepository#insertOnConflictDoNothing}.
 *
 * <p>Constructor + private setters only — {@link RevivalService} owns every
 * write. Story 3.2 will add a friend-gift writer in the same package.
 */
@Entity
@Table(name = "revival_events")
public class RevivalEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "giver_user_id")
    private Long giverUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RevivalSource source;

    @Column(name = "source_subtype", length = 20)
    private String sourceSubtype;

    @Column(name = "points_spent", nullable = false)
    private short pointsSpent;

    @Column(name = "pool_after", nullable = false)
    private int poolAfter;

    @Column(name = "eliminated_at", nullable = false)
    private Instant eliminatedAt;

    @Column(nullable = false)
    private boolean succeeded = true;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected RevivalEvent() {}

    public RevivalEvent(
            long roomId,
            long userId,
            Long giverUserId,
            RevivalSource source,
            String sourceSubtype,
            short pointsSpent,
            int poolAfter,
            Instant eliminatedAt,
            Instant occurredAt) {
        this.roomId = roomId;
        this.userId = userId;
        this.giverUserId = giverUserId;
        this.source = source;
        this.sourceSubtype = sourceSubtype;
        this.pointsSpent = pointsSpent;
        this.poolAfter = poolAfter;
        this.eliminatedAt = eliminatedAt;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }
    public Long getGiverUserId() { return giverUserId; }
    public RevivalSource getSource() { return source; }
    public String getSourceSubtype() { return sourceSubtype; }
    public short getPointsSpent() { return pointsSpent; }
    public int getPoolAfter() { return poolAfter; }
    public Instant getEliminatedAt() { return eliminatedAt; }
    public boolean isSucceeded() { return succeeded; }
    public Instant getOccurredAt() { return occurredAt; }
}
