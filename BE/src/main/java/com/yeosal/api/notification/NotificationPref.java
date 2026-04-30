package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 1:1 with {@code users}. Defaults match the SQL: standard nudges enabled,
 * quiet hours 22:00-08:00 local time.
 */
@Entity
@Table(name = "notification_prefs")
public class NotificationPref {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "goal_nudge_enabled", nullable = false)
    private boolean goalNudgeEnabled = true;

    @Column(name = "reflection_nudge_enabled", nullable = false)
    private boolean reflectionNudgeEnabled = true;

    @Column(name = "event_hooks_enabled", nullable = false)
    private boolean eventHooksEnabled = true;

    @Column(name = "quiet_start_hour", nullable = false)
    private short quietStartHour = 22;

    @Column(name = "quiet_end_hour", nullable = false)
    private short quietEndHour = 8;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationPref() {}

    public NotificationPref(User user) {
        this.user = user;
        this.userId = user.getId();
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public User getUser() { return user; }
    public boolean isGoalNudgeEnabled() { return goalNudgeEnabled; }
    public boolean isReflectionNudgeEnabled() { return reflectionNudgeEnabled; }
    public boolean isEventHooksEnabled() { return eventHooksEnabled; }
    public short getQuietStartHour() { return quietStartHour; }
    public short getQuietEndHour() { return quietEndHour; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setGoalNudgeEnabled(boolean v) { this.goalNudgeEnabled = v; }
    public void setReflectionNudgeEnabled(boolean v) { this.reflectionNudgeEnabled = v; }
    public void setEventHooksEnabled(boolean v) { this.eventHooksEnabled = v; }
    public void setQuietStartHour(short v) { this.quietStartHour = v; }
    public void setQuietEndHour(short v) { this.quietEndHour = v; }
}
