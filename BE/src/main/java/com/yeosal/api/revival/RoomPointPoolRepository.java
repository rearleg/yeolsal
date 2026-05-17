package com.yeosal.api.revival;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for the per-room point-pool counter cache
 * ({@link RoomPointPool}). Three responsibilities — all writer-side:
 *
 * <ul>
 *   <li>{@link #selectForUpdate(long)} — native {@code SELECT ... FOR
 *       UPDATE} to lock the single pool row per Architecture §4.6 +
 *       NFR-9.2.4. MUST run inside {@code @Transactional}; the row-level
 *       lock releases at commit.</li>
 *   <li>{@link #incrementTotal(long, int)} — native UPDATE bumping
 *       {@code total} by the signed delta and refreshing
 *       {@code last_event_at} to {@code now()}. Caller guarantees the
 *       row lock is held.</li>
 *   <li>{@link #findTotalByRoomId(long)} — post-update read for the
 *       service to surface {@code roomPointPoolAfter} in the response DTO
 *       and the realtime payload. Returns the updated value because the
 *       UPDATE has flushed inside the same transaction.</li>
 * </ul>
 *
 * <p>Story 4.1 may lift the counter-cache writes into a richer per-event
 * pool service; Story 3.1 leaves the contract minimal so downstream
 * stories can extend without breaking the read path.
 */
public interface RoomPointPoolRepository extends JpaRepository<RoomPointPool, Long> {

    /**
     * Native row-level pessimistic lock. The row MUST already exist —
     * V11 step 15 backfills one row per room at migration time, and
     * {@code RoomService} mints one on every {@code create}.
     * Returns {@code Optional.empty()} if the row is missing so the
     * service can surface a clear error rather than NPE-ing on a null
     * total.
     */
    @Query(
            value = "select room_id, total, last_event_at from room_point_pool "
                    + "where room_id = :roomId for update",
            nativeQuery = true)
    Optional<RoomPointPool> selectForUpdate(@Param("roomId") long roomId);

    /**
     * Native bump: {@code total + :delta}, refresh {@code last_event_at}.
     * Returns the row count (1 on success, 0 if the row is missing).
     * Caller MUST have already acquired the row lock via
     * {@link #selectForUpdate(long)} earlier in the same transaction; the
     * DB-side CHECK ({@code total >= 0}) catches negative-going overflow
     * as defence-in-depth.
     */
    @Modifying
    @Query(
            value = "update room_point_pool set total = total + :delta, "
                    + "last_event_at = now() where room_id = :roomId",
            nativeQuery = true)
    int incrementTotal(@Param("roomId") long roomId, @Param("delta") int delta);

    /**
     * Post-update total read for the response DTO and the realtime
     * payload. {@code null} when no row exists — callers coalesce to
     * {@code 0} defensively (same pattern as
     * {@code SurvivalStateRepository.findRoomPointPoolTotal}).
     */
    @Query(value = "select total from room_point_pool where room_id = :roomId",
            nativeQuery = true)
    Integer findTotalByRoomId(@Param("roomId") long roomId);
}
