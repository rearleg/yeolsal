package com.yeosal.api.room;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
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
    public ApiResponse<RoomDto> create(Authentication auth, @Valid @RequestBody CreateRoomRequest body) {
        User me = currentUser.require(auth);
        return ApiResponse.of(RoomDto.from(roomService.create(me, body.name())));
    }

    @GetMapping
    public ApiResponse<List<RoomDto>> mine(Authentication auth) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.myRooms(me).stream().map(RoomDto::from).toList());
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<MemberDto>> members(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomService.members(me, id).stream().map(MemberDto::from).toList());
    }

    @PostMapping("/{id}/invites")
    public ApiResponse<InviteDto> createInvite(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        return ApiResponse.of(InviteDto.from(roomService.createInvite(me, id, DEFAULT_INVITE_TTL)));
    }

    @PostMapping("/join")
    public ApiResponse<MemberDto> join(Authentication auth, @Valid @RequestBody JoinRequest body) {
        User me = currentUser.require(auth);
        return ApiResponse.of(MemberDto.from(roomService.joinByCode(me, body.code().trim())));
    }

    @DeleteMapping("/{id}/members/me")
    public ResponseEntity<Void> leave(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        roomService.leave(me, id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRoomRequest(@NotBlank @Size(max = 80) String name) {}
    public record JoinRequest(@NotBlank @Size(min = 4, max = 32) String code) {}

    public record RoomDto(long id, String name, long ownerId, int maxMembers) {
        static RoomDto from(Room room) {
            return new RoomDto(room.getId(), room.getName(), room.getOwner().getId(), room.getMaxMembers());
        }
    }

    public record MemberDto(long roomId, long userId, String nickname, RoomRole role) {
        static MemberDto from(RoomMember m) {
            return new MemberDto(m.getRoom().getId(), m.getUser().getId(), m.getUser().getNickname(), m.getRole());
        }
    }

    public record InviteDto(long id, long roomId, String code, Instant expiresAt) {
        static InviteDto from(RoomInvite i) {
            return new InviteDto(i.getId(), i.getRoom().getId(), i.getCode(), i.getExpiresAt());
        }
    }
}
