package com.yeosal.api.room;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * Page of room ids ordered by primary key. Used by the monthly
     * evaluator scheduler so the per-room transaction never has to load
     * the full {@link Room} entity (or any of its lazy associations) into
     * the page-iteration scope. Each page is its own short-lived read,
     * which keeps memory bounded as the rooms table grows.
     */
    @Query("select r.id from Room r order by r.id")
    Page<Long> findAllIdsOrderById(Pageable pageable);
}
