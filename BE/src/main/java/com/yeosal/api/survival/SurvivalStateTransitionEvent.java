package com.yeosal.api.survival;

import java.time.Instant;

/**
 * In-process event carrying a survival-state transition across the
 * AFTER_COMMIT boundary (Spring {@code TransactionalEventListener}).
 * The evaluator publishes this from inside its {@code @Transactional}
 * method; the listener fires only after the surrounding transaction
 * commits, so a rollback (e.g. constraint violation post-publish) never
 * lights up the realtime fan-out.
 *
 * @param roomId             affected room
 * @param userId             affected member
 * @param ownerUserId        room owner — receives the immediate private
 *                           signal alongside the affected user on RED
 *                           transitions (Story 1.2 AC7)
 * @param fromStatus         the lifecycle status before this transition
 * @param toStatus           the lifecycle status after this transition
 * @param occurredAt         the single {@code Clock.instant()} snapshot
 *                           shared with {@code last_state_change_at}
 *                           and (for RED) {@code eliminated_at} — AC9
 * @param broadVisibilityAt  {@code occurredAt + 24h} for RED transitions;
 *                           {@code null} for ACTIVE→YELLOW (no privacy
 *                           gate on YELLOW)
 */
public record SurvivalStateTransitionEvent(
        long roomId,
        long userId,
        Long ownerUserId,
        SurvivalStatus fromStatus,
        SurvivalStatus toStatus,
        Instant occurredAt,
        Instant broadVisibilityAt) {}
