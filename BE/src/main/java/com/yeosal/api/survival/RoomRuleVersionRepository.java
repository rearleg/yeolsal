package com.yeosal.api.survival;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRuleVersionRepository extends JpaRepository<RoomRuleVersion, Long> {

    /**
     * The currently effective rule for {@code roomId} given a target month
     * (KST {@code YYYY-MM}). The lookup is descending so the most recent
     * row whose effective month is at-or-before the target wins.
     *
     * <p>V11 step 14 backfills one row per room at install time, so this
     * call is expected to never return empty in production. Callers
     * (BE-4 evaluator) throw {@code IllegalStateException} on empty —
     * that's a data-shape bug, not a user-facing condition.
     */
    Optional<RoomRuleVersion>
            findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                    long roomId, String yearMonth);
}
