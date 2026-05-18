package com.yeosal.api.room.chat;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.5 — kudos send endpoint (POST /api/v1/rooms/{id}/kudos).
 *
 * <p>Authentication is enforced by the global security chain. Membership /
 * eligibility / friendship gates live inside {@link KudosService#sendKudos}
 * (AC3 sequence), not here — the service is the single transactional
 * authority for the kudos write so a controller-level membership precheck
 * would only duplicate that logic without changing the outcome (the
 * service rejects non-members with the same {@code ForbiddenException}).
 *
 * <p>The {@link ResponseStatus} annotation is load-bearing: epics line 582
 * mandates {@code 201 Created} and Spring's default {@code @PostMapping}
 * status is 200 OK. Returning the DTO via {@link ApiResponse#of} keeps
 * the wire envelope shape consistent across the API.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class KudosController {

    private final KudosService kudosService;
    private final CurrentUser currentUser;

    public KudosController(KudosService kudosService, CurrentUser currentUser) {
        this.kudosService = kudosService;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/kudos")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KudosDto> send(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody KudosRequest body) {
        User me = currentUser.require(auth);
        return ApiResponse.of(
                kudosService.sendKudos(id, me, body.targetUserId(), body.message()));
    }
}
