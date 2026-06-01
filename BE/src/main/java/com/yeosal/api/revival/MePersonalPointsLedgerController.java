package com.yeosal.api.revival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.4 AC2 — viewer-scoped personal-points ledger read endpoint.
 *
 * <p>{@code GET /api/v1/me/personal-points-ledger?roomId={id}} returns the
 * calling user's full ledger for the requested room, DESC by occurredAt.
 * Powers the {@code <LedgerDetailScreen>} drill-in from the Wallet UI.
 *
 * <p>The endpoint is intentionally {@code currentUser}-scoped (no
 * {@code ?userId=} parameter) per AC4 privacy — the only way to read
 * personal-points history is to authenticate as that user. The defence-
 * in-depth model has three layers:
 * <ol>
 *   <li>This controller never accepts a {@code userId} query parameter.</li>
 *   <li>The native SQL {@code where user_id = :userId} clause receives
 *       {@code currentUser.require(auth).getId()} only.</li>
 *   <li>FE domain hooks never accept a {@code userId} argument
 *       (enforced by file convention in {@code lib/query/hooks/}).</li>
 * </ol>
 *
 * <p>Separate controller from {@link MeFriendGiftController} (receipts +
 * has-given) and {@link MeFriendGiftTargetsController} (giver-side
 * eligibility) so each {@code /me/*} resource family stays cohesive.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MePersonalPointsLedgerController {

    private final PersonalPointsLedgerRepository ledger;
    private final CurrentUser currentUser;

    public MePersonalPointsLedgerController(
            PersonalPointsLedgerRepository ledger, CurrentUser currentUser) {
        this.ledger = ledger;
        this.currentUser = currentUser;
    }

    /**
     * Returns one entry per ledger row in DESC chronological order. Empty
     * list when nothing matches — never 404. Cross-room and cross-user
     * filtering happens at the SQL layer
     * ({@link PersonalPointsLedgerRepository#findByUserIdAndRoomIdOrderByOccurredAtDesc}).
     */
    @GetMapping("/personal-points-ledger")
    @Transactional(readOnly = true)
    public ApiResponse<List<LedgerEntryDto>> ledger(
            Authentication auth, @RequestParam long roomId) {
        User me = currentUser.require(auth);
        List<PersonalPointsLedger> rows =
                ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(me.getId(), roomId);
        if (rows.isEmpty()) {
            return ApiResponse.of(List.of());
        }
        List<LedgerEntryDto> out = new ArrayList<>(rows.size());
        for (PersonalPointsLedger row : rows) {
            out.add(new LedgerEntryDto(
                    row.getId(),
                    row.getRoomId(),
                    row.getDelta(),
                    row.getReason().name(),
                    row.getOccurredAt(),
                    row.getRevivalEventId()));
        }
        return ApiResponse.of(out);
    }
}
