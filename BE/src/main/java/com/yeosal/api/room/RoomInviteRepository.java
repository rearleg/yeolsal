package com.yeosal.api.room;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomInviteRepository extends JpaRepository<RoomInvite, Long> {

    boolean existsByCodeAndRevokedAtIsNull(String code);

    List<RoomInvite> findByRoom(Room room);

    @Query("""
            select i from RoomInvite i
            where i.code = :code
              and i.revokedAt is null
              and (i.expiresAt is null or i.expiresAt > :now)
            """)
    Optional<RoomInvite> findActiveByCode(@Param("code") String code, @Param("now") Instant now);
}
