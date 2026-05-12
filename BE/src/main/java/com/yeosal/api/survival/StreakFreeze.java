package com.yeosal.api.survival;

import com.yeosal.api.room.Room;
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
import java.time.LocalDate;

/**
 * Monthly streak-freeze record (V11 step 4). Story 1.2's evaluator consumes
 * one freeze per {@code (user_id, month)} pair as the first defense before
 * any {@code ACTIVE → YELLOW} transition.
 *
 * <p>The {@code ux_streak_freezes_user_month} partial unique index is the
 * second line of defense — concurrent evaluator instances race against
 * {@code INSERT ... ON CONFLICT DO NOTHING} so exactly one freeze persists.
 */
@Entity
@Table(name = "streak_freezes")
public class StreakFreeze {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "applied_date", nullable = false)
    private LocalDate appliedDate;

    @Column(name = "month", nullable = false, length = 7)
    private String month;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StreakFreeze() {}

    public StreakFreeze(User user, Room room, LocalDate appliedDate, String month) {
        this.user = user;
        this.room = room;
        this.appliedDate = appliedDate;
        this.month = month;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Room getRoom() { return room; }
    public LocalDate getAppliedDate() { return appliedDate; }
    public String getMonth() { return month; }
    public Instant getCreatedAt() { return createdAt; }
}
