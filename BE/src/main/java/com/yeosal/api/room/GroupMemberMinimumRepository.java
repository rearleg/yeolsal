package com.yeosal.api.room;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberMinimumRepository extends JpaRepository<GroupMemberMinimum, Long> {

    Optional<GroupMemberMinimum> findByRoomIdAndUserId(Long roomId, Long userId);

    List<GroupMemberMinimum> findByRoomId(Long roomId);

    /**
     * Race-safe upsert for the (room_id, user_id) pair. Two concurrent PATCH
     * requests for the same legacy/missing row would otherwise both observe
     * an empty {@code findByRoomIdAndUserId} and the loser would hit the
     * UNIQUE constraint at commit. Pushing the dedup down to Postgres lets
     * both requests succeed and the second one re-read the winner's row.
     */
    @Modifying
    @Query(value = """
            insert into group_member_minimums (room_id, user_id, min_daily_goal_days)
            values (:roomId, :userId, :minDailyGoalDays)
            on conflict (room_id, user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId,
            @Param("minDailyGoalDays") short minDailyGoalDays);
}
