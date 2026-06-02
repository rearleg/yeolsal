package com.yeosal.api.survival;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RoomRuleVersionRepository extends JpaRepository<RoomRuleVersion, Long> {

    /**
     * The currently effective rule for {@code roomId} given a target month
     * (KST {@code YYYY-MM}). The lookup is descending so the most recent
     * row whose effective month is at-or-before the target wins.
     *
     * <p>V11 step 14 backfills one row per room at install time, so this
     * call is expected to never return empty in production. Callers throw
     * {@code IllegalStateException} on empty because that is a data-shape
     * bug, not a user-facing condition.
     */
    Optional<RoomRuleVersion>
            findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                    long roomId, String yearMonth);

    /** Exact-month lookup for a pending edit. */
    Optional<RoomRuleVersion> findByRoomIdAndEffectiveFromMonth(
            long roomId, String effectiveFromMonth);

    /**
     * Race-free upsert against the UNIQUE {@code (room_id, effective_from_month)}
     * constraint. A concurrent re-edit by the same leader (double-tap)
     * collides on the unique key; PostgreSQL's {@code ON CONFLICT DO UPDATE}
     * resolves it deterministically so both requests return success.
     *
     * <p>The JSONB payload MUST be passed as a {@code text} parameter and
     * cast via {@code cast(?3 as jsonb)} — without the cast Postgres rejects
     * the parameter binding as {@code column is of type jsonb but expression
     * is of type character varying}.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into room_rule_versions
                (room_id, effective_from_month, rule_payload, created_by_user_id, created_at)
            values (?1, ?2, cast(?3 as jsonb), ?4, now())
            on conflict (room_id, effective_from_month) do update set
                rule_payload = excluded.rule_payload,
                created_by_user_id = excluded.created_by_user_id,
                created_at = excluded.created_at
            """, nativeQuery = true)
    int upsertRule(long roomId, String yearMonth, String payloadJson, long createdByUserId);

    /** Seed the first effective rule for rooms created after the migration backfill. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into room_rule_versions
                (room_id, effective_from_month, rule_payload, created_by_user_id, created_at)
            values (
                ?1,
                ?2,
                jsonb_build_object('preset', 'DAILY_UPDATE', 'weekendInclude', true),
                ?3,
                now())
            on conflict (room_id, effective_from_month) do nothing
            """, nativeQuery = true)
    int insertDefaultIfAbsent(long roomId, String yearMonth, long createdByUserId);
}
