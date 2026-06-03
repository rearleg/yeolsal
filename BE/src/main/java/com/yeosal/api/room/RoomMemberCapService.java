package com.yeosal.api.room;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 5.2 — leader-only edit point for {@code rooms.max_members} with
 * next-month-only application. Writes the pending pair
 * ({@code pending_max_members}, {@code pending_max_members_effective_from_month})
 * inside a single {@code @Transactional} method; promotion into
 * {@code rooms.max_members} happens lazily on the first
 * {@link RoomService#requireRoom(long)} call once the effective KST month
 * is reached (handed off to {@link RoomCapPromotionService} in a
 * {@code REQUIRES_NEW} writable boundary).
 *
 * <p>Race semantics: same-leader double-tap from the FE produces two
 * concurrent PATCH transactions targeting the same {@code rooms} row.
 * PostgreSQL row-level locks serialize the two; last-write-wins. No
 * advisory lock is needed.
 *
 * <p>nextMonthKST mirrors Story 5.1's {@code RoomRuleService.nextMonthKST}
 * byte-for-byte to keep both leader-edit surfaces aligned on the same
 * calendar-month boundary contract (project-context.md:92,270).
 */
@Service
public class RoomMemberCapService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CAP_MIN = 2;
    private static final int CAP_MAX = 30;

    private final RoomService roomService;
    private final Clock clock;

    public RoomMemberCapService(RoomService roomService, Clock clock) {
        this.roomService = roomService;
        this.clock = clock;
    }

    @Transactional
    public RoomService.RoomSummary updateMemberCap(User me, long roomId, int maxMembers) {
        if (maxMembers < CAP_MIN || maxMembers > CAP_MAX) {
            throw new BadRequestException("정원은 2에서 30 사이여야 합니다.");
        }
        Room room = roomService.requireRoomForUpdate(roomId);
        roomService.requireLeader(room, me);

        String nextMonth = nextMonthKST();
        short newPending = (short) maxMembers;
        if (room.getPendingMaxMembers() != null
                && room.getPendingMaxMembers() == newPending
                && nextMonth.equals(room.getPendingMaxMembersEffectiveFromMonth())) {
            return RoomService.RoomSummary.from(room);
        }
        room.setPendingMaxMembers(newPending);
        room.setPendingMaxMembersEffectiveFromMonth(nextMonth);
        return RoomService.RoomSummary.from(room);
    }

    private String nextMonthKST() {
        LocalDate todayKst = LocalDate.ofInstant(clock.instant(), KST);
        return YearMonth.from(todayKst).plusMonths(1).toString();
    }
}
