package com.yeosal.api.room;

import java.util.Set;

/**
 * Whitelist for the per-room and per-member daily-goal-day minimum.
 * Mirrors the {@code chk_rooms_min_daily_goal_days} and
 * {@code chk_group_member_minimum_days} CHECK constraints introduced by
 * {@code V6__room_minimums_and_warnings.sql}. {@code 31} means
 * "every day of that calendar month"; the application caps it by the
 * actual length of the month at evaluation time.
 *
 * <p>Constants are kept as {@code int} so request validation never
 * narrows attacker-controlled values into the allowed set via
 * {@code Number.shortValue()} truncation (e.g. {@code 65546 -> 10}).
 * Callers cast to {@code short} only when persisting to the JPA column.
 */
public final class GoalMinimumDays {
    public static final int DEFAULT = 10;
    public static final Set<Integer> ALLOWED = Set.of(10, 15, 20, 31);

    private GoalMinimumDays() {}

    public static boolean isAllowed(int value) {
        return ALLOWED.contains(value);
    }
}
