package com.yeosal.api.ceremony;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 7.1 — Final-3 poster read endpoint. Membership is enforced inside
 * {@link FinalThreeService#getPosterForMember(long, YearMonth, long)} so
 * the controller stays thin (matches the {@code SurvivalStateController}
 * precedent). Authentication runs at the security filter; an
 * unauthenticated request returns 401 before reaching this method.
 *
 * <p>The {@code yearMonth} path variable is parsed via {@link YearMonth#parse}
 * with a {@link DateTimeParseException} → {@link BadRequestException} bridge
 * — the project has no {@code ConstraintViolationException} handler, so
 * Bean-Validation {@code @Pattern} would surface as 5xx.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class PosterController {

    private final FinalThreeService finalThreeService;
    private final CurrentUser currentUser;

    public PosterController(FinalThreeService finalThreeService, CurrentUser currentUser) {
        this.finalThreeService = finalThreeService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{roomId}/posters/{yearMonth}")
    public ApiResponse<PosterDto> getPoster(
            Authentication auth,
            @PathVariable long roomId,
            @PathVariable String yearMonth) {
        User me = currentUser.require(auth);
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(yearMonth);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("yearMonth는 YYYY-MM 형식이어야 합니다.");
        }
        FinalThreePoster poster = finalThreeService.getPosterForMember(roomId, parsed, me.getId());
        return ApiResponse.of(PosterDto.from(poster));
    }
}
