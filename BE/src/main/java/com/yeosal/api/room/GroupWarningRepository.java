package com.yeosal.api.room;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupWarningRepository extends JpaRepository<GroupWarning, Long> {

    /** Idempotency check for the monthly evaluator. */
    boolean existsByRoomIdAndUserIdAndEvaluationMonth(
            Long roomId, Long userId, LocalDate evaluationMonth);

    /**
     * Atomic idempotency primitive for the monthly evaluator. The unique
     * {@code (room_id, user_id, evaluation_month)} key handles the dedup;
     * a re-fired cron writes 0 rows. Returning the affected count lets the
     * caller branch on "I just won this month's audit" vs "already done".
     */
    @Modifying
    @Query(value = """
            insert into group_warnings (
                room_id, user_id, evaluation_month,
                completed_days, required_days, warning_count_after
            )
            values (
                :roomId, :userId, :evaluationMonth,
                :completedDays, :requiredDays, :warningCountAfter
            )
            on conflict (room_id, user_id, evaluation_month) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId,
            @Param("evaluationMonth") LocalDate evaluationMonth,
            @Param("completedDays") short completedDays,
            @Param("requiredDays") short requiredDays,
            @Param("warningCountAfter") short warningCountAfter);
}
