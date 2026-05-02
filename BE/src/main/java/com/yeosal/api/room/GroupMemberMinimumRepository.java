package com.yeosal.api.room;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberMinimumRepository extends JpaRepository<GroupMemberMinimum, Long> {

    Optional<GroupMemberMinimum> findByRoomIdAndUserId(Long roomId, Long userId);

    List<GroupMemberMinimum> findByRoomId(Long roomId);
}
