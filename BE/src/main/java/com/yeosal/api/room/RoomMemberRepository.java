package com.yeosal.api.room;

import com.yeosal.api.user.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

    long countByRoom(Room room);

    List<RoomMember> findByRoom(Room room);

    /**
     * Fetch-join variant used by query-budget-sensitive read paths
     * (Story 1.3 AC10 roster — collapses the per-member {@code rm.getUser()}
     * lazy load that the plain {@link #findByRoom(Room)} would trigger
     * inside the response-shaping loop).
     */
    @Query("""
            select rm
            from RoomMember rm
            join fetch rm.user
            where rm.room = :room
            """)
    List<RoomMember> findByRoomFetchingUser(@Param("room") Room room);

    List<RoomMember> findByUser(User user);

    /**
     * Direct {@code Room} fetch — avoids the 1+N lazy load you would get from
     * {@code findByUser(user).map(RoomMember::getRoom)} when the caller only
     * cares about the room (e.g. the chat fan-out hooks in {@code DailyService}).
     */
    @Query("""
            select rm.room
            from RoomMember rm
            where rm.user = :user
            """)
    List<Room> findRoomsByUser(@Param("user") User user);

    Optional<RoomMember> findByRoomAndUser(Room room, User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select rm
            from RoomMember rm
            where rm.room.id = :roomId
              and rm.user.id = :userId
            """)
    Optional<RoomMember> findByRoomIdAndUserIdForUpdate(
            @Param("roomId") long roomId,
            @Param("userId") long userId);

    /**
     * Lightweight existence check used by the STOMP {@code SUBSCRIBE}
     * authoriser ({@link com.yeosal.api.realtime.JwtChannelInterceptor}).
     * Avoids loading full Room/User entities just to decide whether the
     * connecting principal may listen on a {@code /topic/rooms.{id}.*}
     * destination.
     */
    boolean existsByRoomIdAndUserId(Long roomId, Long userId);

    void deleteByRoomAndUser(Room room, User user);

    @Query("""
            select case when count(a) > 0 then true else false end
            from RoomMember a, RoomMember b
            where a.room = b.room
              and a.user = :viewer
              and b.user = :target
            """)
    boolean existsSharedRoom(@Param("viewer") User viewer, @Param("target") User target);

    @Query("""
            select distinct b.user from RoomMember a, RoomMember b
            where a.room = b.room
              and a.user = :user
              and b.user <> :user
            """)
    List<User> findRoomMates(@Param("user") User user);

    /**
     * Pick the longest-tenured ACTIVE candidates for automatic leader
     * promotion when the current leader transitions to RED. Excludes the
     * eliminated leader explicitly; the ACTIVE filter already excludes them
     * via {@code survival_state.status}, but the explicit exclusion protects
     * against visibility races around the producing transaction.
     * The {@code id ASC} tail-key is a deterministic tiebreaker for the
     * very-rare case where two members share {@code joined_at} (V11
     * backfill case: legacy {@code room_members} rows can land in
     * {@code survival_state} with sub-millisecond-identical timestamps).
     *
     * <p>Returns an empty list when no eligible candidate exists — the
     * room is dormant; leadership stays with the eliminated leader.
     */
    @Query("""
            select rm
            from RoomMember rm
            where rm.room.id = :roomId
              and rm.user.id <> :excludedUserId
              and exists (
                  select 1 from com.yeosal.api.survival.SurvivalState s
                  where s.room.id = rm.room.id
                    and s.user.id = rm.user.id
                    and s.status = com.yeosal.api.survival.SurvivalStatus.ACTIVE
              )
            order by rm.joinedAt asc, rm.id asc
            """)
    List<RoomMember> findLongestTenuredActiveCandidates(
            @Param("roomId") long roomId,
            @Param("excludedUserId") long excludedUserId);
}
