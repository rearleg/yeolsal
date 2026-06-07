package com.yeosal.api.ceremony;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link FinalThreePoster}. The base
 * {@link JpaRepository#existsById(Object)} already covers the idempotency
 * check inside {@link FinalThreeService}; no derived
 * {@code existsByRoomIdAndYearMonth} method is needed.
 */
public interface FinalThreePosterRepository
        extends JpaRepository<FinalThreePoster, FinalThreePosterId> {
}
