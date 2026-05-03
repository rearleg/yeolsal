package com.yeosal.api.room.chat;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * Cursor-based descending fetch. Pass {@code Long.MAX_VALUE} for the
     * first page so the caller doesn't have to branch on null. The
     * {@code (room_id, id desc)} index from V7 makes this an index-only
     * range scan.
     */
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(
            Long roomId, Long beforeId, Pageable pageable);
}
