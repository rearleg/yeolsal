package com.yeosal.api.room;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 5.2 — lazy promoter for the room's pending member-cap edit. Runs in a
 * {@code REQUIRES_NEW} writable transaction so readOnly callers (e.g.
 * {@code RoomService.myRooms}, {@code RoomService.members},
 * {@code RoomService.todayForRoom}) can trigger the promotion without
 * tripping Hibernate's readOnly flush guard.
 *
 * <p>The promotion contract:
 * <ul>
 *   <li>NO-OP when {@code pending_max_members} or
 *       {@code pending_max_members_effective_from_month} is NULL.</li>
 *   <li>NO-OP when the current calendar month (KST) is strictly less than
 *       the effective-from month — the edit isn't due yet.</li>
 *   <li>Otherwise flush {@code pending_max_members} into
 *       {@code rooms.max_members} and NULL out both pending columns. The DB
 *       CHECK {@code chk_rooms_pending_cap_consistency} keeps the half-state
 *       impossible.</li>
 * </ul>
 *
 * <p>Idempotent: a second call on the same room after promotion is a no-op
 * because the pending columns are already NULL.
 */
@Service
public class RoomCapPromotionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoomRepository rooms;
    private final Clock clock;

    public RoomCapPromotionService(RoomRepository rooms, Clock clock) {
        this.rooms = rooms;
        this.clock = clock;
    }

    /**
     * Atomically promote the pending member-cap into {@code max_members} if
     * the effective-from month has been reached. Returns {@code true} when a
     * write happened so the caller can refresh its in-memory copy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean promotePendingCapIfDue(long roomId) {
        Room room = rooms.findById(roomId).orElse(null);
        if (room == null) {
            return false;
        }
        String pendingMonth = room.getPendingMaxMembersEffectiveFromMonth();
        Short pending = room.getPendingMaxMembers();
        if (pendingMonth == null || pending == null) {
            return false;
        }
        String currentMonth = YearMonth.from(
                LocalDate.ofInstant(clock.instant(), KST)
        ).toString();
        if (currentMonth.compareTo(pendingMonth) < 0) {
            return false;
        }
        room.setMaxMembers(pending);
        room.setPendingMaxMembers(null);
        room.setPendingMaxMembersEffectiveFromMonth(null);
        // dirty-check flushes on @Transactional(REQUIRES_NEW) commit
        return true;
    }
}
