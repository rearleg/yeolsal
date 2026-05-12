package com.yeosal.api.room;

import com.yeosal.api.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rooms")
public class Room {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "max_members", nullable = false)
    private short maxMembers = 12;

    @Column(name = "min_daily_goal_days", nullable = false)
    private short minDailyGoalDays = (short) GoalMinimumDays.DEFAULT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Room() {}

    public Room(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }

    public Room(String name, User owner, short minDailyGoalDays) {
        this(name, owner);
        this.minDailyGoalDays = minDailyGoalDays;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        // Defense-in-depth clamp. The API-level reject lives in
        // CreateRoomRequest (@Min/@Max) + RoomService whitelist check so
        // clients get a 400 VALIDATION with a clear message; this guard is
        // a last-resort fallback for direct-entity test paths and legacy
        // code that bypasses the controller.
        if (maxMembers < 2 || maxMembers > 30) {
            maxMembers = 12;
        }
        if (!GoalMinimumDays.isAllowed(minDailyGoalDays)) {
            minDailyGoalDays = (short) GoalMinimumDays.DEFAULT;
        }
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public User getOwner() { return owner; }
    public short getMaxMembers() { return maxMembers; }
    public short getMinDailyGoalDays() { return minDailyGoalDays; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setOwner(User owner) { this.owner = owner; }
    public void setMaxMembers(short maxMembers) { this.maxMembers = maxMembers; }
    public void setMinDailyGoalDays(short minDailyGoalDays) {
        this.minDailyGoalDays = minDailyGoalDays;
    }
}
