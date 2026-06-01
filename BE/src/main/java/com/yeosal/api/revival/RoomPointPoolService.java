package com.yeosal.api.revival;

import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.1 AC3 — single chokepoint for {@code room_point_pool} mutations
 * triggered by revivals (FREE_TICKET / PERSONAL_POINTS / FRIEND_GIFT
 * sources). Two responsibilities:
 *
 * <ol>
 *   <li><strong>Negative-delta guard.</strong> {@code delta <= 0} throws
 *       {@link IllegalArgumentException} BEFORE the SQL UPDATE fires.
 *       The global {@link com.yeosal.api.common.ApiExceptionHandler} maps
 *       {@code IllegalArgumentException} to {@code 400 VALIDATION}, so the
 *       FE sees a clean 400 rather than a 5xx Sentry alert. The DB-side
 *       {@code CHECK (total >= 0)} (V11 step 7) stays as defence-in-depth
 *       and should never fire from a code-path call site in v1.</li>
 *   <li><strong>Lock + UPDATE + publish ordering.</strong> Acquires the
 *       row-level lock via {@link RoomPointPoolRepository#selectForUpdate}
 *       (Architecture §4.6 secondary serialiser), increments {@code total}
 *       through {@link RoomPointPoolRepository#incrementTotal}, and
 *       publishes the in-process {@link PointPoolChangeEvent} for the
 *       AFTER_COMMIT realtime listener
 *       ({@link RoomPointPoolRealtimeListener}) to fan out on
 *       {@code /topic/rooms.{roomId}.points}.</li>
 * </ol>
 *
 * <p>{@link Propagation#MANDATORY} enforces that the caller already opened
 * a transaction — the service refuses to run outside one. If a future
 * caller forgets {@code @Transactional}, Spring throws
 * {@link org.springframework.transaction.IllegalTransactionStateException}
 * immediately at the controller boundary instead of silently autocommitting
 * each statement, which would defeat the row-lock + advisory-lock
 * invariants. Do NOT switch to {@code REQUIRED} (would mask the bug) or
 * {@code REQUIRES_NEW} (would break the shared advisory lock).
 *
 * <p>The Architecture §4.4 advisory lock is owned by {@link RevivalService}
 * (the triple key {@code roomId/userId/eliminatedAtEpochMillis} is a
 * revival-flow concern, not a pool concern). This service only owns the
 * row-level secondary serialiser plus the UPDATE and the in-process
 * publish.
 *
 * <p>The caller is expected to have pre-acquired the same row lock earlier
 * in the same transaction (to derive {@code pool_after} for the
 * {@code revival_events} INSERT). The second {@code FOR UPDATE} here is a
 * no-op cost in Postgres because the row is already locked by the calling
 * transaction. The double acquisition is intentional defence — the service
 * MUST refuse to run without the lock held even if a future caller
 * forgets the pre-INSERT acquisition.
 */
@Service
public class RoomPointPoolService {

    private final RoomPointPoolRepository roomPointPool;
    private final ApplicationEventPublisher eventPublisher;

    public RoomPointPoolService(
            RoomPointPoolRepository roomPointPool,
            ApplicationEventPublisher eventPublisher) {
        this.roomPointPool = roomPointPool;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Applies a positive {@code delta} to the room's pool row, returning
     * the post-increment total. Publishes a {@link PointPoolChangeEvent}
     * carrying the same value alongside the caller's
     * {@code sourceRevivalEventId} so subscribers can dedupe by event id.
     *
     * @return the post-increment {@code total}, equal to
     *         {@code pool.getTotal() + delta} (the snapshot is taken inside
     *         the row lock so the value matches the
     *         {@code revival_events.pool_after} column the caller wrote
     *         earlier in the same transaction).
     * @throws IllegalArgumentException when {@code delta <= 0} — mapped to
     *         {@code 400 VALIDATION} by the global exception handler.
     * @throws IllegalStateException when the pool row is missing for
     *         {@code roomId} (legacy room missed V11 backfill) or vanished
     *         mid-transaction (zero rows updated).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyDelta(
            long roomId, int delta, long sourceRevivalEventId, Instant occurredAt) {
        if (delta <= 0) {
            throw new IllegalArgumentException(
                    "pool delta must be positive, got=" + delta);
        }
        RoomPointPool pool = roomPointPool.selectForUpdate(roomId)
                .orElseThrow(() -> new IllegalStateException(
                        "room_point_pool row missing for roomId=" + roomId));
        int newTotal = pool.getTotal() + delta;
        int updated = roomPointPool.incrementTotal(roomId, delta);
        if (updated == 0) {
            throw new IllegalStateException(
                    "room_point_pool row vanished mid-transaction: roomId="
                            + roomId);
        }
        eventPublisher.publishEvent(new PointPoolChangeEvent(
                roomId, delta, newTotal, sourceRevivalEventId, occurredAt));
        return newTotal;
    }
}
