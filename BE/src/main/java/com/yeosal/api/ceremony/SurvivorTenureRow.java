package com.yeosal.api.ceremony;

import java.time.Instant;

/**
 * Package-private projection emitted by {@link FinalThreeService}'s native
 * survivors query. Field order matches the SQL projection: nickname first
 * for {@link SvgRenderer} consumption, then user_id as the deterministic
 * tie-breaker for tests, then joined_at for ordering verification.
 */
record SurvivorTenureRow(String nickname, long userId, Instant joinedAt) {
}
