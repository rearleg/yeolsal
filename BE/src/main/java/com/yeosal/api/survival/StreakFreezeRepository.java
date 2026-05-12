package com.yeosal.api.survival;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StreakFreezeRepository extends JpaRepository<StreakFreeze, Long> {

    boolean existsByUserIdAndMonth(long userId, String month);

    Optional<StreakFreeze> findByUserIdAndMonth(long userId, String month);

    /**
     * Race-safe upsert keyed on the {@code ux_streak_freezes_user_month}
     * partial unique index. Mirrors {@link com.yeosal.api.room.GroupWarningRepository#insertIfAbsent}
     * — push the dedup into Postgres so the service never has to translate
     * a JDBC integrity violation back into a "already used this month" branch.
     *
     * <p>Returns 1 when the row was actually inserted, 0 when the unique
     * constraint short-circuited.
     */
    @Modifying
    @Query(value = """
            insert into streak_freezes (user_id, room_id, applied_date, month)
            values (:userId, :roomId, :appliedDate, :month)
            on conflict (user_id, month) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") long userId,
            @Param("roomId") long roomId,
            @Param("appliedDate") LocalDate appliedDate,
            @Param("month") String month);
}
