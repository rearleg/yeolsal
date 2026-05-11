package com.yeosal.api.survival;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurvivalStateRepository extends JpaRepository<SurvivalState, Long> {

    Optional<SurvivalState> findByRoomIdAndUserId(long roomId, long userId);

    List<SurvivalState> findByRoomId(long roomId);
}
