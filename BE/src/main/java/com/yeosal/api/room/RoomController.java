package com.yeosal.api.room;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yeosal.api.common.BadRequestException;

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
        // Cap defaults to 12 when the client omits it (older FE bundles without
        // the picker UI still create rooms). The whitelist check on the full
        // int lives in the service so a direct curl bypassing @Min/@Max still
        // gets a clean 400 instead of silent JPA prePersist clamping.
        int max = body.maxMembers() == null
                ? RoomService.DEFAULT_MAX_MEMBERS
                : body.maxMembers();
        return ApiResponse.of(roomService.create(me, body.name(), min, max));
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

    /**
     * Per-member daily snapshot for the group dashboard. {@code date} is the
     * FE's entry-date in ISO {@code yyyy-MM-dd}; we parse it manually here
     * (rather than via Spring's converter) so a malformed value yields a
     * 400 with a localized message instead of a 500 binding error.
     */
    @GetMapping("/{id}/today")
    public ApiResponse<List<RoomService.MemberTodayDto>> today(
            Authentication auth,
            @PathVariable long id,
            @RequestParam("date") String date
    ) {
        User me = currentUser.require(auth);
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("date 형식이 올바르지 않습니다. (yyyy-MM-dd)");
        }
        return ApiResponse.of(roomService.todayForRoom(me, id, parsed));
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

    @PatchMapping("/{id}/members/me/minimum")
    public ApiResponse<RoomService.MemberSummary> updateMyMinimum(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody UpdateMinimumRequest body
    ) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.updateMyMinimum(me, id, body.minDailyGoalDays()));
    }

    public record CreateRoomRequest(
            @NotBlank @Size(max = 80) String name,
            // Nullable for backwards-compat with FE bundles that haven't shipped
            // the picker yet; the controller falls back to the default and the
            // service rejects values outside the {10, 15, 20, 31} whitelist.
            Integer minDailyGoalDays,
            // Nullable for the same reason — older bundles omit it. Bean
            // Validation rejects out-of-range at the DTO boundary; the service
            // double-checks for clients that bypass validation (FR-8.1.1).
            @Min(2) @Max(30) Integer maxMembers
    ) {}

    public record JoinRequest(@NotBlank @Size(min = 4, max = 32) String code) {}

    public record UpdateMinimumRequest(@NotNull Integer minDailyGoalDays) {}
}
