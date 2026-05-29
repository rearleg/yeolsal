package com.yeosal.api.revival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.4 AC3 — viewer-scoped lifetime received-revival history
 * (per room, all 3 sources).
 *
 * <p>{@code GET /api/v1/me/received-revivals?roomId={id}} returns every
 * successful revival the calling user has received in the requested room,
 * covering FREE_TICKET / PERSONAL_POINTS / FRIEND_GIFT sources. Powers
 * the {@code <ReceivedRevivalsDetailScreen>} drill-in from the Wallet UI.
 *
 * <p>Donor nickname is populated only for FRIEND_GIFT rows per FR-8.3.5
 * (receiver-only donor visibility — AC9 privacy invariant carried from
 * Story 3.2). Donor lookups are batched to avoid N+1; the room name is
 * resolved once because the endpoint is per-room.
 *
 * <p>Separate controller from {@link MeFriendGiftController} (which owns
 * the 7-day FRIEND_GIFT window for the daily-entry caption — different
 * semantics) so the read-side seam stays narrow. AC3 explicitly recommends
 * the new-endpoint path rather than overloading the 7-day endpoint with a
 * source filter.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeReceivedRevivalsController {

    private final RevivalEventRepository revivalEvents;
    private final UserRepository users;
    private final RoomRepository rooms;
    private final CurrentUser currentUser;

    public MeReceivedRevivalsController(
            RevivalEventRepository revivalEvents,
            UserRepository users,
            RoomRepository rooms,
            CurrentUser currentUser) {
        this.revivalEvents = revivalEvents;
        this.users = users;
        this.rooms = rooms;
        this.currentUser = currentUser;
    }

    /**
     * Returns one entry per successful revival row received in the
     * requested room, DESC by occurredAt. Empty list when nothing
     * matches — never 404.
     *
     * <p>{@code @Transactional(readOnly = true)} so any association
     * resolution (donor / room) stays inside the transaction boundary
     * ({@code open-in-view: false} project rule).
     */
    @GetMapping("/received-revivals")
    @Transactional(readOnly = true)
    public ApiResponse<List<ReceivedRevivalDto>> received(
            Authentication auth, @RequestParam long roomId) {
        User me = currentUser.require(auth);
        List<RevivalEvent> rows =
                revivalEvents.findReceivedRevivalsByRoom(me.getId(), roomId);
        if (rows.isEmpty()) {
            return ApiResponse.of(List.of());
        }

        // Batched donor load — only FRIEND_GIFT rows have a non-null
        // giverUserId; the distinct() collapses repeats when the same
        // donor appears across multiple historical gifts in the same room.
        List<Long> donorIds = rows.stream()
                .filter(r -> r.getSource() == RevivalSource.FRIEND_GIFT)
                .map(RevivalEvent::getGiverUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, String> donorNickname = new HashMap<>();
        if (!donorIds.isEmpty()) {
            for (User u : users.findAllById(donorIds)) {
                donorNickname.put(u.getId(), u.getNickname());
            }
        }

        // Endpoint is per-room, so a single room lookup suffices for the
        // entire response. Defensively default to "" if the row is gone
        // (e.g. room deletion mid-query) — mirrors MeFriendGiftController's
        // getOrDefault pattern.
        String roomName = rooms.findById(roomId).map(Room::getName).orElse("");

        List<ReceivedRevivalDto> out = new ArrayList<>(rows.size());
        for (RevivalEvent row : rows) {
            Long donorId = row.getSource() == RevivalSource.FRIEND_GIFT
                    ? row.getGiverUserId() : null;
            // Use get() (returns null on miss) rather than getOrDefault(..., "")
            // — an empty-string nickname surfaces a "님이 보낸 회생권" orphan
            // caption on the FE when the donor's user row was hard-deleted.
            // The FE's `donorNickname != null` branch handles the null path
            // by falling through to the source caption.
            String donorName = donorId == null ? null : donorNickname.get(donorId);
            out.add(new ReceivedRevivalDto(
                    row.getId(),
                    row.getRoomId(),
                    roomName,
                    row.getSource().name(),
                    donorId,
                    donorName,
                    row.getOccurredAt()));
        }
        return ApiResponse.of(out);
    }
}
