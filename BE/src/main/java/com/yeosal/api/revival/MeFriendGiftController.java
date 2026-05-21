package com.yeosal.api.revival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.2 AC7 + AC10 — viewer-scoped friend-gift read endpoints.
 *
 * <ul>
 *   <li>{@code GET /me/friend-gift-receipts} — 7-day FRIEND_GIFT receive
 *       window with donor nickname + room name resolved server-side.
 *       Powers the {@code <SevenDayFootnote>} caption on the daily-entry
 *       screen.</li>
 *   <li>{@code GET /me/has-given-friend-gift} — pure
 *       have-I-ever-sent-a-FRIEND_GIFT boolean. Powers the M3.5
 *       lifetime-1 overlay fallback when the POST response field doesn't
 *       reach the component.</li>
 * </ul>
 *
 * <p>Separate controller from {@code SurvivalStateController} (which owns
 * {@code /me/survival}) because the friend-gift surface is a separate
 * resource family — keeping the seam clean lets Story 3.4 (wallet UI)
 * extend without resurrecting cross-controller coupling.
 *
 * <p>All read paths run under {@code @Transactional(readOnly = true)} so
 * the donor/user/room association loads resolve inside the transactional
 * boundary (open-in-view: false project rule).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeFriendGiftController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RevivalEventRepository revivalEvents;
    private final UserRepository users;
    private final RoomRepository rooms;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MeFriendGiftController(
            RevivalEventRepository revivalEvents,
            UserRepository users,
            RoomRepository rooms,
            CurrentUser currentUser,
            Clock clock) {
        this.revivalEvents = revivalEvents;
        this.users = users;
        this.rooms = rooms;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Story 3.2 AC7. Returns the calling user's FRIEND_GIFT receives in the
     * last 7 KST days, ordered by {@code occurredAt DESC}. Empty list when
     * no qualifying rows — never 404.
     *
     * <p>AC9 privacy invariant — the query is keyed on {@code user_id =
     * me} which is the RECEIVER column for FRIEND_GIFT rows. The endpoint
     * MUST NEVER surface rows where the caller is the
     * {@code giver_user_id}; that's a separate surface owned by Story 3.4.
     */
    @GetMapping("/friend-gift-receipts")
    @Transactional(readOnly = true)
    public ApiResponse<List<FriendGiftReceiptDto>> receipts(Authentication auth) {
        User me = currentUser.require(auth);
        LocalDate kstToday = LocalDate.ofInstant(clock.instant(), KST);
        LocalDate kstWindowStart = kstToday.minusDays(6);

        List<RevivalEvent> rows = revivalEvents.findFriendGiftReceiptsWithin7Days(
                me.getId(), kstWindowStart, kstToday);
        if (rows.isEmpty()) {
            return ApiResponse.of(List.of());
        }

        // Batched donor + room loads — avoid N+1.
        List<Long> donorIds = rows.stream()
                .map(RevivalEvent::getGiverUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        List<Long> roomIds = rows.stream()
                .map(RevivalEvent::getRoomId)
                .distinct()
                .toList();

        Map<Long, String> donorNickname = new HashMap<>();
        for (User u : users.findAllById(donorIds)) {
            donorNickname.put(u.getId(), u.getNickname());
        }
        Map<Long, String> roomName = new HashMap<>();
        for (Room r : rooms.findAllById(roomIds)) {
            roomName.put(r.getId(), r.getName());
        }

        List<FriendGiftReceiptDto> out = new ArrayList<>(rows.size());
        for (RevivalEvent row : rows) {
            Long donorId = row.getGiverUserId();
            LocalDate occurredKst = LocalDate.ofInstant(row.getOccurredAt(), KST);
            int daysSinceRevival = (int) ChronoUnit.DAYS.between(occurredKst, kstToday);
            out.add(new FriendGiftReceiptDto(
                    row.getId(),
                    row.getRoomId(),
                    roomName.getOrDefault(row.getRoomId(), ""),
                    donorId == null ? 0L : donorId,
                    donorId == null ? "" : donorNickname.getOrDefault(donorId, ""),
                    row.getOccurredAt(),
                    daysSinceRevival));
        }
        return ApiResponse.of(out);
    }

    /**
     * Story 3.2 AC10. Returns {@code true} iff the calling user has ever
     * sent a FRIEND_GIFT row. Implemented via the same EXCLUDING-self
     * query the M3.5 marker uses (BE-4), passing {@code -1L} as the
     * exclude id to mean "exclude nothing" (no row has id {@code -1L}).
     */
    @GetMapping("/has-given-friend-gift")
    @Transactional(readOnly = true)
    public ApiResponse<HasGivenFriendGiftDto> hasGiven(Authentication auth) {
        User me = currentUser.require(auth);
        boolean has = revivalEvents.existsFriendGiftSendByGiver(me.getId(), -1L);
        return ApiResponse.of(new HasGivenFriendGiftDto(has));
    }
}
