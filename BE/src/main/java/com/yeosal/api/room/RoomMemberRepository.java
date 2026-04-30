package com.yeosal.api.room;

import com.yeosal.api.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

    long countByRoom(Room room);

    List<RoomMember> findByRoom(Room room);

    List<RoomMember> findByUser(User user);

    Optional<RoomMember> findByRoomAndUser(Room room, User user);

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
}
