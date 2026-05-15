package com.yeosal.api.survival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-room survival aggregation surface (Architecture §6.4, Epic 1 retro
 * T4). Returns one {@link MeSurvivalEntryDto} per room the authenticated
 * user is a member of — the FE Home-tab consumes this to decide cross-room
 * treatments (e.g. {@code isSpectatorAcrossAllRooms}, Story 2.1).
 *
 * <p>Authorization: authenticated only. The user is always self, so the
 * per-room AC3 RED-cooldown mask does not apply here; the response carries
 * the raw {@link SurvivalStatus} per room.
 *
 * <p>Separate controller from {@link SurvivalStateController} because the
 * class-level mapping there is {@code /api/v1/rooms} while this endpoint
 * lives under {@code /api/v1/me}. Package-by-feature keeps both in the
 * {@code survival} package (project-context BE rule).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeSurvivalController {

    private final SurvivalStateService survivalStateService;
    private final CurrentUser currentUser;

    public MeSurvivalController(
            SurvivalStateService survivalStateService, CurrentUser currentUser) {
        this.survivalStateService = survivalStateService;
        this.currentUser = currentUser;
    }

    @GetMapping("/survival")
    public ApiResponse<List<MeSurvivalEntryDto>> mine(Authentication auth) {
        User me = currentUser.require(auth);
        return ApiResponse.of(survivalStateService.mySurvivalAcrossRooms(me.getId()));
    }
}
