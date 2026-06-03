package com.yeosal.api.room;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 5.2 — leader-only member-cap edit endpoint. Mounted at the
 * architecture-locked path {@code /api/v1/rooms/{id}/members/cap}
 * (Architecture §6.4 line 813). Returns the extended {@link
 * RoomService.RoomSummary} so the FE can render the new pending fields
 * without a second roundtrip.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomMemberCapController {

    private final RoomMemberCapService capService;
    private final CurrentUser currentUser;

    public RoomMemberCapController(
            RoomMemberCapService capService,
            CurrentUser currentUser) {
        this.capService = capService;
        this.currentUser = currentUser;
    }

    @PatchMapping("/{id}/members/cap")
    public ApiResponse<RoomService.RoomSummary> updateMemberCap(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody UpdateMemberCapRequest body) {
        User me = currentUser.require(auth);
        return ApiResponse.of(
                capService.updateMemberCap(me, id, body.maxMembers()));
    }
}
