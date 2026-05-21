package com.yeosal.api.revival;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.3 AC1 — eligible-target read endpoint for the wallet badge.
 *
 * <p>{@code GET /api/v1/me/friend-gift-targets} returns the calling
 * user's per-room aggregation of eligible friend-gift targets. The
 * endpoint is caller-scoped (no {@code ?userId=}); spectator givers see
 * an empty list because {@link FriendGiftTargetQuery} gates
 * {@code sst_giver.status in ('ACTIVE','YELLOW')}.
 *
 * <p>Separate controller from {@link MeFriendGiftController} (Story 3.2 —
 * receipts + has-given) so the read-side seam stays narrow: receipts is
 * receiver-side; this endpoint is giver-side.
 *
 * <p>{@code @Transactional(readOnly = true)} so any lazy association the
 * caller's session might touch resolves inside the boundary
 * ({@code open-in-view: false} project rule).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeFriendGiftTargetsController {

    private final FriendGiftTargetQuery targetQuery;
    private final CurrentUser currentUser;

    public MeFriendGiftTargetsController(
            FriendGiftTargetQuery targetQuery, CurrentUser currentUser) {
        this.targetQuery = targetQuery;
        this.currentUser = currentUser;
    }

    /**
     * Returns one summary entry per room the caller is in that has at
     * least one eligible (RED/SPECTATOR + ACCEPTED-friendship) target.
     * Rooms without any eligible target are omitted (the FE-side balance
     * gate may further filter the result; that gate is intentionally
     * client-side per AC1).
     *
     * <p>Empty list when nothing qualifies — never 404.
     */
    @GetMapping("/friend-gift-targets")
    @Transactional(readOnly = true)
    public ApiResponse<List<FriendGiftTargetSummaryDto>> targets(Authentication auth) {
        User me = currentUser.require(auth);
        List<FriendGiftTargetQuery.EligibleFriendRow> rows =
                targetQuery.findEligibleTargets(me.getId());
        if (rows.isEmpty()) {
            return ApiResponse.of(List.of());
        }

        // Group by roomId preserving the SQL ordering (room_id ASC) so
        // the response is stable for the FE.
        Map<Long, List<FriendGiftTargetQuery.EligibleFriendRow>> grouped =
                new LinkedHashMap<>();
        for (FriendGiftTargetQuery.EligibleFriendRow row : rows) {
            grouped.computeIfAbsent(row.roomId(), k -> new ArrayList<>()).add(row);
        }

        List<FriendGiftTargetSummaryDto> out = new ArrayList<>(grouped.size());
        for (Map.Entry<Long, List<FriendGiftTargetQuery.EligibleFriendRow>> e
                : grouped.entrySet()) {
            List<FriendGiftTargetQuery.EligibleFriendRow> roomRows = e.getValue();
            String roomName = roomRows.get(0).roomName();
            List<EligibleFriendDto> friends = new ArrayList<>(roomRows.size());
            for (FriendGiftTargetQuery.EligibleFriendRow row : roomRows) {
                friends.add(new EligibleFriendDto(
                        row.targetUserId(),
                        row.targetNickname(),
                        row.targetStatus(),
                        row.targetEliminatedAt()));
            }
            // SQL already provides eliminated_at DESC; defensive resort
            // so a downstream re-ordering bug never breaks picker UX.
            friends.sort(Comparator.comparing(
                    EligibleFriendDto::eliminatedAt, Comparator.reverseOrder()));
            out.add(new FriendGiftTargetSummaryDto(
                    e.getKey(), roomName, friends.size(), friends));
        }
        return ApiResponse.of(out);
    }
}
