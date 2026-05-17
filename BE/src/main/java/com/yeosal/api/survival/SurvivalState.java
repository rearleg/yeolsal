package com.yeosal.api.survival;

import com.yeosal.api.room.Room;
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

/**
 * Materialized survival lifecycle row for a single (room, user) pair —
 * source of truth per Architecture §4.1. New rows are minted by
 * {@link SurvivalStateService#initializeOnJoin}; daily transitions are
 * the responsibility of Story 1.2's evaluator.
 *
 * <p>{@code status} has no public setter on purpose: every transition must
 * pass through {@link SurvivalStateService}, which owns the AC3/AC4 grace
 * guard. Package-private mutators exist for the service only.
 */
@Entity
@Table(
        name = "survival_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "user_id"}))
public class SurvivalState {

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
    @Column(nullable = false, length = 16)
    private SurvivalStatus status = SurvivalStatus.ACTIVE;

    @Column(name = "last_state_change_at", nullable = false)
    private Instant lastStateChangeAt;

    @Column(name = "eliminated_at")
    private Instant eliminatedAt;

    @Column(name = "broad_visibility_at")
    private Instant broadVisibilityAt;

    /**
     * Trial-grace deadline. {@code null} for legacy rows backfilled by V11
     * step (13) — those members are not in grace. New joins via
     * {@link SurvivalStateService#initializeOnJoin} set this to
     * {@code joinedAt + 14 days}; the guard's comparison is exclusive
     * (grace ends at the instant {@code grace_ends_at}, not after).
     */
    @Column(name = "grace_ends_at")
    private Instant graceEndsAt;

    protected SurvivalState() {}

    public SurvivalState(Room room, User user, Instant graceEndsAt) {
        this.room = room;
        this.user = user;
        this.graceEndsAt = graceEndsAt;
    }

    @PrePersist
    void prePersist() {
        if (lastStateChangeAt == null) {
            lastStateChangeAt = Instant.now();
        }
        if (status == null) {
            status = SurvivalStatus.ACTIVE;
        }
    }

    public Long getId() { return id; }
    public Room getRoom() { return room; }
    public User getUser() { return user; }
    public SurvivalStatus getStatus() { return status; }
    public Instant getLastStateChangeAt() { return lastStateChangeAt; }
    public Instant getEliminatedAt() { return eliminatedAt; }
    public Instant getBroadVisibilityAt() { return broadVisibilityAt; }
    public Instant getGraceEndsAt() { return graceEndsAt; }

    /** Package-private — only {@link SurvivalStateService} drives transitions. */
    void setStatus(SurvivalStatus status) {
        this.status = status;
    }

    /** Package-private — only {@link SurvivalStateService} drives transitions. */
    void setLastStateChangeAt(Instant lastStateChangeAt) {
        this.lastStateChangeAt = lastStateChangeAt;
    }

    /** Package-private — set only by Story 1.2 (RED transition) via service. */
    void setEliminatedAt(Instant eliminatedAt) {
        this.eliminatedAt = eliminatedAt;
    }

    /** Package-private — set by Story 2.x (spectator broad visibility). */
    void setBroadVisibilityAt(Instant broadVisibilityAt) {
        this.broadVisibilityAt = broadVisibilityAt;
    }

    /**
     * Story 3.1 — revival lifecycle transition. Flips the row back to
     * {@link SurvivalStatus#ACTIVE} and clears both the elimination
     * timestamp and the broad-visibility cooldown in one atomic
     * operation, so the four field writes share a single semantic step
     * (rather than four loose setters callable from outside the
     * package).
     *
     * <p>Public on the entity (not package-private) because the revival
     * domain lives in the sibling {@code revival/} package — exposing a
     * single named operation preserves the "service-mediated mutation"
     * intent without leaking four cross-package setters.
     */
    public void markRevived(Instant now) {
        this.status = SurvivalStatus.ACTIVE;
        this.lastStateChangeAt = now;
        this.eliminatedAt = null;
        this.broadVisibilityAt = null;
    }
}
