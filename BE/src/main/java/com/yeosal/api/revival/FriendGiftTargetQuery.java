package com.yeosal.api.revival;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Story 3.3 AC1 — eligibility query for the wallet badge surface. Returns
 * one row per eligible (room, target-friend) pair where:
 *
 * <ul>
 *   <li>The caller (giver) is a room member,</li>
 *   <li>The caller's own {@code survival_state.status} is one of
 *       {@code ACTIVE} / {@code YELLOW} (NFR-9.2.5 — spectator givers
 *       cannot tap the badge; the SQL gates this server-side),</li>
 *   <li>The candidate target's {@code survival_state.status} is one of
 *       {@code RED} / {@code SPECTATOR} (eliminated),</li>
 *   <li>The candidate is NOT the caller (self-target forbidden),</li>
 *   <li>An {@code ACCEPTED} friendship row connects the two users in
 *       either direction (mirrors Story 3.2 {@link FriendGiftEligibilityQuery}
 *       OR-clause shape — the V1 friendships table uses
 *       {@code requester_id} / {@code addressee_id}).</li>
 * </ul>
 *
 * <p>The balance precondition (giver has ≥ 5 personal points in the
 * room) is intentionally <strong>not</strong> SQL-side: per AC1 the BE
 * returns the full eligibility list and the FE applies the balance
 * filter on the client. This keeps the query cheap (no SUM aggregate per
 * room) and lets the FE recompute eligibility from cached ledger
 * movements without re-fetching.
 *
 * <p>Native query so the join shape is transparent. Mirrors
 * {@link FriendGiftEligibilityQuery} for owner / package conventions:
 * standalone {@code @Component} (not a {@link RevivalEventRepository}
 * method) because the SQL roots are {@code survival_state} /
 * {@code rooms} / {@code friendships} / {@code users}, not
 * {@code revival_events}.
 */
@Component
public class FriendGiftTargetQuery {

    @PersistenceContext
    private final EntityManager entityManager;

    public FriendGiftTargetQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Returns the flat list of eligible target rows for the caller.
     * Ordering is {@code (room_id ASC, eliminated_at DESC)} for stable
     * FE rendering; the controller groups by {@code roomId} in-memory to
     * assemble the summary DTOs.
     */
    public List<EligibleFriendRow> findEligibleTargets(long giverUserId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select
                    sst.room_id              as room_id,
                    r.name                   as room_name,
                    target_user.id           as target_user_id,
                    target_user.nickname     as target_nickname,
                    sst.status               as target_status,
                    sst.eliminated_at        as target_eliminated_at
                from survival_state sst
                join rooms r on r.id = sst.room_id
                join room_members rm_giver on rm_giver.room_id = sst.room_id
                    and rm_giver.user_id = :giverUserId
                join survival_state sst_giver on sst_giver.room_id = sst.room_id
                    and sst_giver.user_id = :giverUserId
                join users target_user on target_user.id = sst.user_id
                join friendships f on (
                    (f.requester_id = :giverUserId and f.addressee_id = sst.user_id)
                    or (f.requester_id = sst.user_id and f.addressee_id = :giverUserId)
                ) and f.status = 'ACCEPTED'
                where sst.status in ('RED','SPECTATOR')
                  and sst.user_id <> :giverUserId
                  and sst_giver.status in ('ACTIVE','YELLOW')
                order by sst.room_id asc, sst.eliminated_at desc
                """)
                .setParameter("giverUserId", giverUserId)
                .getResultList();

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(FriendGiftTargetQuery::mapRow).toList();
    }

    private static EligibleFriendRow mapRow(Object[] r) {
        long roomId = ((Number) r[0]).longValue();
        String roomName = (String) r[1];
        long targetUserId = ((Number) r[2]).longValue();
        String targetNickname = (String) r[3];
        String targetStatus = (String) r[4];
        Instant eliminatedAt = toInstant(r[5]);
        return new EligibleFriendRow(
                roomId, roomName, targetUserId, targetNickname,
                targetStatus, eliminatedAt);
    }

    private static Instant toInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant i) {
            return i;
        }
        if (raw instanceof Timestamp t) {
            return t.toInstant();
        }
        throw new IllegalStateException(
                "Unexpected eliminated_at column type: " + raw.getClass());
    }

    /**
     * Flat row shape returned by the native query. The controller groups
     * by {@code roomId} via {@code Collectors.groupingBy} to assemble
     * {@link FriendGiftTargetSummaryDto} responses.
     */
    public record EligibleFriendRow(
            long roomId,
            String roomName,
            long targetUserId,
            String targetNickname,
            String targetStatus,
            Instant targetEliminatedAt) {}
}
