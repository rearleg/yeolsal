package com.yeosal.api.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Per-(room, user) minimum-day-of-month override + monthly warning ledger.
 * Models {@code group_member_minimums} from V6. The DB-side FK references
 * {@code room_members(room_id, user_id) ON DELETE CASCADE} so when a member
 * leaves (or the room is deleted) Postgres reaps this row automatically;
 * we deliberately do not model that FK as a JPA association so the entity
 * stays free of composite-key plumbing — the room/user IDs are kept as
 * scalar columns and resolved through the repository.
 */
@Entity
@Table(
        name = "group_member_minimums",
        uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "user_id"})
)
public class GroupMemberMinimum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "min_daily_goal_days", nullable = false)
    private short minDailyGoalDays;

    @Column(name = "warning_count", nullable = false)
    private short warningCount = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GroupMemberMinimum() {}

    public GroupMemberMinimum(long roomId, long userId, short minDailyGoalDays) {
        this.roomId = roomId;
        this.userId = userId;
        this.minDailyGoalDays = minDailyGoalDays;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }
    public short getMinDailyGoalDays() { return minDailyGoalDays; }
    public short getWarningCount() { return warningCount; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMinDailyGoalDays(short v) {
        if (!GoalMinimumDays.isAllowed(v)) {
            throw new IllegalArgumentException(
                    "minDailyGoalDays must be one of " + GoalMinimumDays.ALLOWED);
        }
        this.minDailyGoalDays = v;
    }

    public void setWarningCount(short v) {
        if (v < 0 || v > 2) {
            throw new IllegalArgumentException(
                    "warningCount must be between 0 and 2 (DB chk_group_member_warning_count)");
        }
        this.warningCount = v;
    }

    /**
     * Increments the warning ledger, capping at 2 to honour the DB
     * {@code chk_group_member_warning_count} CHECK. Domain transitions for the
     * "auto-leave at 2 warnings" rule should consult {@link #getWarningCount()}
     * before calling this — once we're at 2 the next failure should trigger
     * removal, not another increment.
     */
    public void incrementWarningCount() {
        if (warningCount < 2) {
            warningCount = (short) (warningCount + 1);
        }
    }
}
