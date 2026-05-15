package com.yeosal.api.revival;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalPointsLedgerRepository
        extends JpaRepository<PersonalPointsLedger, Long> {

    /**
     * Used by the BE-7.3 integration test to assert SURVIVAL idempotency:
     * a re-run of {@code SurvivalStateEvaluatorJob#runEvaluation(date)} must
     * write zero additional ledger rows. Stories 3.x will likely refine this
     * into a richer query API.
     */
    long countByUserIdAndRoomIdAndReason(long userId, long roomId, LedgerReason reason);

    /**
     * Story 2.1 AC7 — running personal-points balance for the
     * {@code (user, room)} pair, summed across every {@code LedgerReason}.
     * Used by {@code SurvivalStateService.mySurvivalAcrossRooms} to populate
     * the spectator-only {@code WalletPreview} block.
     *
     * <p>The JPQL {@code coalesce(sum(...), 0)} guarantees a non-null result
     * even when no ledger rows match; the boxed {@code Integer} return type
     * is required because {@code SUM} of an integer column produces a
     * {@code BIGINT} subtype that Hibernate widens before COALESCE — callers
     * MUST still coalesce defensively at the service layer.
     */
    @Query("""
            select coalesce(sum(l.delta), 0)
            from PersonalPointsLedger l
            where l.userId = :userId and l.roomId = :roomId
            """)
    Integer sumDeltaByUserIdAndRoomId(
            @Param("userId") long userId, @Param("roomId") long roomId);
}
