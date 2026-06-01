package com.yeosal.api.revival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.1 AC5 — dedicated per-room point-pool snapshot endpoint
 * ({@code GET /api/v1/rooms/{id}/points}).
 *
 * <p>Architecture §6.4 promised this resource; Stories 3.1/3.2/3.4 shipped
 * the underlying writes and the STOMP fan-out but never the REST read
 * path. {@link com.yeosal.api.survival.MeSurvivalEntryDto} continues to
 * carry a cross-room {@code roomPointPool} aggregation (more efficient
 * for the spectator Today tab — one query, N rooms); this controller is
 * the per-room source of truth that the Wallet route subscribes to.
 *
 * <p>Privacy invariant — non-members get {@code 403 FORBIDDEN} regardless
 * of whether the room exists, matching the {@code ChatController}
 * precedent (avoids leaking room existence to outsiders). Missing pool
 * rows (legacy rooms that pre-date V11 step 15 backfill) coalesce to
 * {@code total = 0, lastEventAt = null} so the FE never sees a null
 * total.
 *
 * <p>Lives in the {@code revival/} package per package-by-feature — the
 * pool is a revival-economy concern. Mirrors the precedent set by
 * {@link MePersonalPointsLedgerController} and {@link MeReceivedRevivalsController}
 * keeping their controllers grouped with their DTOs and services rather
 * than overloading {@code RoomController}.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomPointsController {

    private final RoomPointPoolRepository roomPointPool;
    private final RoomMemberRepository roomMembers;
    private final CurrentUser currentUser;

    public RoomPointsController(
            RoomPointPoolRepository roomPointPool,
            RoomMemberRepository roomMembers,
            CurrentUser currentUser) {
        this.roomPointPool = roomPointPool;
        this.roomMembers = roomMembers;
        this.currentUser = currentUser;
    }

    /**
     * Returns the current pool snapshot for {@code roomId}. Member-guard
     * fires before the pool lookup so non-members never observe a
     * difference between "room does not exist" and "you are not a member".
     *
     * <p>{@code @Transactional(readOnly = true)} per the read-controller
     * convention ({@link MeFriendGiftController}, {@link MeReceivedRevivalsController}
     * precedent) so any incidental lazy-association resolution stays
     * inside the transaction boundary ({@code open-in-view: false}).
     */
    @GetMapping("/{id}/points")
    @Transactional(readOnly = true)
    public ApiResponse<RoomPointPoolDto> get(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        if (!roomMembers.existsByRoomIdAndUserId(id, me.getId())) {
            throw new ForbiddenException("방 멤버만 그룹 포인트를 조회할 수 있어요.");
        }
        RoomPointPoolDto dto = roomPointPool.findById(id)
                .map(pool -> new RoomPointPoolDto(
                        pool.getRoomId(), pool.getTotal(), pool.getLastEventAt()))
                .orElseGet(() -> new RoomPointPoolDto(id, 0, null));
        return ApiResponse.of(dto);
    }
}
