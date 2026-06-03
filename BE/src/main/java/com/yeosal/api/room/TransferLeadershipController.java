package com.yeosal.api.room;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 5.2 — leader-only immediate-effect leader transfer endpoint.
 * Mounted at the architecture-locked path
 * {@code /api/v1/rooms/{id}/transfer-leadership} (Architecture §6.4 line 814).
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class TransferLeadershipController {

    private final TransferLeadershipService transferService;
    private final CurrentUser currentUser;

    public TransferLeadershipController(
            TransferLeadershipService transferService,
            CurrentUser currentUser) {
        this.transferService = transferService;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/transfer-leadership")
    public ApiResponse<RoomService.RoomSummary> transferLeadership(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody TransferLeadershipRequest body) {
        User me = currentUser.require(auth);
        return ApiResponse.of(
                transferService.transferLeadership(me, id, body.targetUserId()));
    }
}
