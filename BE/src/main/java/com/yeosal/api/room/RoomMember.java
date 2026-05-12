package com.yeosal.api.room;

import com.yeosal.api.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "room_members", uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "user_id"}))
public class RoomMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomRole role = RoomRole.MEMBER;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected RoomMember() {}

    public RoomMember(Room room, User user, RoomRole role) {
        this.room = room;
        this.user = user;
        this.role = role;
    }

    @PrePersist
    void prePersist() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Room getRoom() { return room; }
    public User getUser() { return user; }
    public RoomRole getRole() { return role; }
    public Instant getJoinedAt() { return joinedAt; }

    public void setRole(RoomRole role) { this.role = role; }

    /**
     * Bind the persisted join timestamp to a caller-supplied {@link Instant}
     * before {@code save()}. {@link RoomService} uses this to anchor
     * {@code joined_at} to the injected {@link java.time.Clock} (matching
     * {@code clock.instant()}), so the {@code survival_state.grace_ends_at
     * = joinedAt + 14 days} write is derived from the exact same instant
     * (AC2). Without this, {@code prePersist} falls back to {@code Instant.now()}
     * and the two writes diverge by however long the surrounding service
     * method takes — a race the daily evaluator could surface as off-by-one.
     */
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
}
