package com.yeosal.api.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Audit row for the monthly minimum-days evaluation. The unique
 * {@code (room_id, user_id, evaluation_month)} key ensures the scheduler
 * stays idempotent — a re-run for the same month is a no-op even if a
 * deploy or pod restart fires the cron twice.
 *
 * <p>{@code evaluation_month} is the first calendar day of the evaluated
 * month in KST (CHECK constraint enforced at the DB level).
 * {@code warning_count_after} is the post-increment value, so the FE can
 * render history without re-deriving from the membership row's current
 * counter.
 */
@Entity
@Table(
        name = "group_warnings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "user_id", "evaluation_month"})
)
public class GroupWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "evaluation_month", nullable = false)
    private LocalDate evaluationMonth;

    @Column(name = "completed_days", nullable = false)
    private short completedDays;

    @Column(name = "required_days", nullable = false)
    private short requiredDays;

    @Column(name = "warning_count_after", nullable = false)
    private short warningCountAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GroupWarning() {}

    public GroupWarning(
            long roomId,
            long userId,
            LocalDate evaluationMonth,
            int completedDays,
            int requiredDays,
            int warningCountAfter
    ) {
        // Mirror the V6 CHECK constraints up-front so caller bugs (negative
        // counts, mid-month dates, warning_count > 2) become Java-side
        // IllegalArgumentException with a clear message instead of opaque
        // Postgres constraint violations at commit time.
        if (evaluationMonth == null || evaluationMonth.getDayOfMonth() != 1) {
            throw new IllegalArgumentException(
                    "evaluationMonth must be the first day of the month");
        }
        if (completedDays < 0 || completedDays > 31) {
            throw new IllegalArgumentException(
                    "completedDays must be between 0 and 31, got " + completedDays);
        }
        if (requiredDays < 1 || requiredDays > 31) {
            throw new IllegalArgumentException(
                    "requiredDays must be between 1 and 31, got " + requiredDays);
        }
        if (warningCountAfter < 1 || warningCountAfter > 2) {
            throw new IllegalArgumentException(
                    "warningCountAfter must be between 1 and 2, got " + warningCountAfter);
        }
        this.roomId = roomId;
        this.userId = userId;
        this.evaluationMonth = evaluationMonth;
        this.completedDays = (short) completedDays;
        this.requiredDays = (short) requiredDays;
        this.warningCountAfter = (short) warningCountAfter;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }
    public LocalDate getEvaluationMonth() { return evaluationMonth; }
    public short getCompletedDays() { return completedDays; }
    public short getRequiredDays() { return requiredDays; }
    public short getWarningCountAfter() { return warningCountAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
