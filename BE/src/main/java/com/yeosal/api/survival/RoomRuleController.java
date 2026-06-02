package com.yeosal.api.survival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST surface for the per-room rule. */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomRuleController {

    private final RoomRuleService roomRuleService;
    private final CurrentUser currentUser;

    public RoomRuleController(RoomRuleService roomRuleService, CurrentUser currentUser) {
        this.roomRuleService = roomRuleService;
        this.currentUser = currentUser;
    }

    @PatchMapping("/{id}/rule")
    public ApiResponse<RoomRuleVersionDto> update(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody UpdateRoomRuleRequest body
    ) {
        User me = currentUser.require(auth);
        return ApiResponse.of(
                roomRuleService.updateRule(me, id, body.preset(), body.weekendInclude()));
    }

    @GetMapping("/{id}/rule")
    public ApiResponse<RoomRuleStateDto> get(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        return ApiResponse.of(roomRuleService.getRule(me, id));
    }
}
