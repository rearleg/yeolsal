package com.yeosal.api.survival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privacy-filtered survival roster surface (Story 1.3, Architecture §4.7 +
 * §6.4). Returns one {@link SurvivalStateDto} per active room member with
 * the server-side privacy mask applied — non-leader, non-self viewers see
 * RED-with-cooldown rows reported as ACTIVE (Architecture §4.14 "Never
 * trust the FE to filter privacy").
 *
 * <p>The {@code /yeolsal} context-path is prefixed automatically by
 * Spring (project-context BE rule); controllers map {@code /api/v1/...}
 * only.
 *
 * <p>Authorization: the endpoint is private — {@link SurvivalStateService#roster}
 * throws {@link com.yeosal.api.common.ForbiddenException} for non-members
 * and {@link com.yeosal.api.common.NotFoundException} for missing rooms.
 * Both map through the single {@code ApiExceptionHandler} advice; no new
 * handler is introduced here.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class SurvivalStateController {

    private final SurvivalStateService survivalStateService;
    private final CurrentUser currentUser;

    public SurvivalStateController(
            SurvivalStateService survivalStateService, CurrentUser currentUser) {
        this.survivalStateService = survivalStateService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/survival")
    public ApiResponse<List<SurvivalStateDto>> roster(
            Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        return ApiResponse.of(survivalStateService.roster(id, me.getId()));
    }
}
