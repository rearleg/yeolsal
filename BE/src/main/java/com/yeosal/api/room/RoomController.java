package com.yeosal.api.room;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    /** Default invite TTL — 7 days from creation. */
    private static final Duration DEFAULT_INVITE_TTL = Duration.ofDays(7);

    private final RoomService roomService;
    private final CurrentUser currentUser;

    public RoomController(RoomService roomService, CurrentUser currentUser) {
        this.roomService = roomService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ApiResponse<RoomService.RoomSummary> create(Authentication auth, @Valid @RequestBody CreateRoomRequest body) {
        User me = currentUser.require(auth);
        // Per-room minimum is optional in the request envelope so older
        // FE bundles that don't surface the picker yet still create rooms.
        // We pass the raw int through to the service so any out-of-range
        // value (including ones that would silently truncate via shortValue)
        // is rejected by the whitelist check instead of being narrowed in.
        int min = body.minDailyGoalDays() == null
                ? GoalMinimumDays.DEFAULT
                : body.minDailyGoalDays();
        return ApiResponse.of(roomService.create(me, body.name(), min));
    }

    @GetMapping
    public ApiResponse<List<RoomService.RoomSummary>> mine(Authentication auth) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.myRooms(me));
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<RoomService.MemberSummary>> members(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.members(me, id));
    }

    @PostMapping("/{id}/invites")
    public ApiResponse<RoomService.InviteSummary> createInvite(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.createInvite(me, id, DEFAULT_INVITE_TTL));
    }

    @PostMapping("/join")
    public ApiResponse<RoomService.MemberSummary> join(Authentication auth, @Valid @RequestBody JoinRequest body) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.joinByCode(me, body.code().trim()));
    }

    @DeleteMapping("/{id}/members/me")
    public ResponseEntity<Void> leave(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        roomService.leave(me, id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRoomRequest(
            @NotBlank @Size(max = 80) String name,
            // Nullable for backwards-compat with FE bundles that haven't shipped
            // the picker yet; the controller falls back to the default and the
            // service rejects values outside the {10, 15, 20, 31} whitelist.
            Integer minDailyGoalDays
    ) {}

    public record JoinRequest(@NotBlank @Size(min = 4, max = 32) String code) {}
}
