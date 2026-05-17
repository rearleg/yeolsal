package com.yeosal.api.revival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-revival endpoint (Story 3.1 AC2/AC3 — FR-8.3.2). Single
 * {@code POST /api/v1/rooms/{id}/revival} that brokers both FREE_TICKET
 * and PERSONAL_POINTS revival flows through {@link RevivalService}.
 *
 * <p>Authentication required (the global security chain enforces this).
 * Room-membership precheck lives here as a cheap fence before the
 * heavier transactional work in the service (mirrors
 * {@code SurvivalStateService.roster} line 339-341).
 *
 * <p>The {@code /yeolsal} context-path is prefixed automatically by
 * Spring (project-context BE rule); this controller maps
 * {@code /api/v1/...} only.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RevivalController {

    private final RevivalService revivalService;
    private final CurrentUser currentUser;
    private final RoomMemberRepository roomMembers;

    public RevivalController(
            RevivalService revivalService,
            CurrentUser currentUser,
            RoomMemberRepository roomMembers) {
        this.revivalService = revivalService;
        this.currentUser = currentUser;
        this.roomMembers = roomMembers;
    }

    @PostMapping("/{id}/revival")
    public ApiResponse<RevivalEventDto> revive(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody RevivalRequest body) {
        User me = currentUser.require(auth);
        if (!roomMembers.existsByRoomIdAndUserId(id, me.getId())) {
            throw new ForbiddenException("방 멤버만 회생할 수 있어요.");
        }
        return ApiResponse.of(
                revivalService.reviveSelf(id, me.getId(), body.source()));
    }
}
