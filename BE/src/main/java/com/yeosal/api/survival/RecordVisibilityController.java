package com.yeosal.api.survival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-room record-visibility opt-in surface (Story 2.3 AC2).
 *
 * <p>Lives under {@code /api/v1/me/...} — siblings with
 * {@link MeSurvivalController}'s {@code GET /api/v1/me/survival}. The
 * authenticated user is always the implicit subject; there is no path
 * variable carrying a user id, which closes off the "set someone else's
 * pref" privilege-escalation surface by construction.
 */
@RestController
@RequestMapping("/api/v1/me/visibility-prefs")
public class RecordVisibilityController {

    private final RecordVisibilityService service;
    private final CurrentUser currentUser;

    public RecordVisibilityController(
            RecordVisibilityService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<VisibilityPrefDto>> list(Authentication auth) {
        User me = currentUser.require(auth);
        return ApiResponse.of(service.listForUser(me));
    }

    @PostMapping
    public ApiResponse<VisibilityPrefDto> upsert(
            Authentication auth,
            @Valid @RequestBody UpsertVisibilityPrefRequest request) {
        User me = currentUser.require(auth);
        return ApiResponse.of(service.upsert(me, request.roomId(), request.shareOnElimination()));
    }
}
