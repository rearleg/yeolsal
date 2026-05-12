package com.yeosal.api.revival;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalPointsLedgerRepository
        extends JpaRepository<PersonalPointsLedger, Long> {

    /**
     * Used by the BE-7.3 integration test to assert SURVIVAL idempotency:
     * a re-run of {@code SurvivalStateEvaluatorJob#runEvaluation(date)} must
     * write zero additional ledger rows. Stories 3.x will likely refine this
     * into a richer query API.
     */
    long countByUserIdAndRoomIdAndReason(long userId, long roomId, LedgerReason reason);
}
