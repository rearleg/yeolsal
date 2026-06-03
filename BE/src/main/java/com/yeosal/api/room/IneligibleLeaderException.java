package com.yeosal.api.room;

/**
 * Thrown when {@code POST /api/v1/rooms/{id}/transfer-leadership} targets a
 * member whose survival state is not promotion-eligible. Per PRD §6.3 +
 * epics:752-754, only members with {@code SurvivalStatus.ACTIVE} or
 * {@code SurvivalStatus.YELLOW} may receive leadership; {@code RED} and
 * {@code SPECTATOR} are rejected so the leader-of-record always carries
 * a surviving membership.
 */
public class IneligibleLeaderException extends RuntimeException {
    public IneligibleLeaderException(String message) {
        super(message);
    }
}
