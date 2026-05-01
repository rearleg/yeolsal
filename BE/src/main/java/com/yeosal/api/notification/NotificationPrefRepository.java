package com.yeosal.api.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationPrefRepository extends JpaRepository<NotificationPref, Long> {

    /**
     * Race-safe lazy default for the per-user pref row. Two near-simultaneous
     * first reads can both observe an empty {@code findById} and try to insert.
     * Relying on {@code save}/{@code saveAndFlush} would let the loser surface
     * a {@link org.springframework.dao.DataIntegrityViolationException} AND
     * poison the surrounding Hibernate session, leaving callers with either a
     * 500 or a "Transaction silently rolled back" cascade. Pushing the dedup
     * down to Postgres via {@code ON CONFLICT DO NOTHING} keeps the JPA
     * session clean — the caller can then re-read the row that the winner
     * wrote. Every column other than {@code user_id} has a DB-side default
     * (see V4__a2_notifications.sql), so the bare insert is sufficient.
     */
    @Modifying
    @Query(value = """
            insert into notification_prefs (user_id)
            values (:userId)
            on conflict (user_id) do nothing
            """, nativeQuery = true)
    void insertDefaultIfAbsent(@Param("userId") Long userId);
}
