package com.yeosal.api.revival;

import com.yeosal.api.survival.SurvivalState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Counter cache row per room — the running pool total surfaced as
 * "그룹 포인트" on the spectator surface. One row per room, primary key is
 * {@code room_id} itself (no synthetic id; V11 step 7 backfills every
 * existing room with {@code total = 0}). The {@code total >= 0} CHECK
 * lives at the DB level — service writers MUST validate balance before
 * decrement attempts so the check fires only as a defence-in-depth net.
 *
 * <p>Architecture §4.6 + NFR-9.2.4 — every increment runs inside the same
 * transaction as the {@code revival_events} INSERT, with
 * {@code SELECT ... FOR UPDATE} on this row to serialise concurrent
 * revivals targeting the same room. The advisory lock from §4.4 prevents
 * the same (room, user, elimination) tuple from racing; the row-level
 * lock prevents two DIFFERENT users in the same room from clobbering each
 * other's {@code total} increment.
 *
 * <p>No public setters — every mutation goes through {@link RevivalService}
 * via the repository's native UPDATE. The package-private writers mirror
 * {@link SurvivalState}'s "service-only writer" pattern.
 */
@Entity
@Table(name = "room_point_pool")
public class RoomPointPool {

    @Id
    @Column(name = "room_id")
    private Long roomId;

    @Column(nullable = false)
    private int total;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    protected RoomPointPool() {}

    public RoomPointPool(long roomId, int total) {
        this.roomId = roomId;
        this.total = total;
    }

    public Long getRoomId() { return roomId; }
    public int getTotal() { return total; }
    public Instant getLastEventAt() { return lastEventAt; }

    /** Package-private — only the revival service drives pool changes. */
    void setTotal(int total) {
        this.total = total;
    }

    /** Package-private — only the revival service drives pool changes. */
    void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }
}
