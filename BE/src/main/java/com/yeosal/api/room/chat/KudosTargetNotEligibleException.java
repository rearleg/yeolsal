package com.yeosal.api.room.chat;

/**
 * Story 3.5 — kudos target's {@code survival_state.status} is not in
 * the eligible set {@code {RED, SPECTATOR}}. Most commonly fired when
 * the FE composes a kudos before realtime delivers a {@code YELLOW →
 * ACTIVE} (or {@code SPECTATOR → ACTIVE} via revival) transition. Also
 * fired defensively when the target's survival_state row is missing
 * entirely (kudos REQUIRES eligibility evidence; absent state is treated
 * as ineligible rather than "pass through").
 *
 * <p>The {@link #CODE} string is the FE-facing branch key; it MUST stay
 * {@code "KUDOS_TARGET_NOT_ELIGIBLE"} so the Friend Gift Modal can show
 * "이 친구는 지금 응원 대상이 아니에요" without parsing the Korean
 * message.
 */
public class KudosTargetNotEligibleException extends RuntimeException {

    /** Stable wire code surfaced via {@code ApiErrorResponse.error.code}. */
    public static final String CODE = "KUDOS_TARGET_NOT_ELIGIBLE";

    public KudosTargetNotEligibleException(String message) {
        super(message);
    }
}
