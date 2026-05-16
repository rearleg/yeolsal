package com.yeosal.api.survival;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordVisibilityPrefRepository
        extends JpaRepository<RecordVisibilityPref, RecordVisibilityPrefId> {

    Optional<RecordVisibilityPref> findByUserIdAndRoomId(long userId, long roomId);

    List<RecordVisibilityPref> findByUserId(long userId);

    /**
     * Race-safe upsert keyed on the V11 PK {@code (user_id, room_id)}.
     * Mirrors {@link SurvivalStateRepository#insertIfAbsent} — push dedup
     * to Postgres via {@code ON CONFLICT … DO UPDATE} so concurrent FE
     * double-taps cannot leave a stale value. Returns the row count
     * affected; the caller re-reads via {@link #findByUserIdAndRoomId} to
     * obtain the post-write {@code updated_at}.
     */
    @Modifying
    @Query(value = """
            insert into record_visibility_prefs (user_id, room_id, share_on_elimination, updated_at)
            values (:userId, :roomId, :share, now())
            on conflict (user_id, room_id) do update
              set share_on_elimination = excluded.share_on_elimination,
                  updated_at = now()
            """, nativeQuery = true)
    int upsertShareOnElimination(
            @Param("userId") long userId,
            @Param("roomId") long roomId,
            @Param("share") boolean share);
}
