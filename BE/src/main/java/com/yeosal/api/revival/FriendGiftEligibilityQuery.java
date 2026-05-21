package com.yeosal.api.revival;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Story 3.2 AC2 — batched eligibility query for the friend-gift push
 * fan-out. Returns the user-ids of every room member who:
 *
 * <ul>
 *   <li>Is a member of the same room as the receiver,</li>
 *   <li>Is NOT the receiver themselves,</li>
 *   <li>Has an {@code ACCEPTED} friendship with the receiver in either
 *       direction (mirrors {@link com.yeosal.api.friend.FriendshipRepository#findBetween}
 *       semantics),</li>
 *   <li>Has a personal-points balance ≥ 5 in that room (the friend-gift
 *       cost — checked via {@code SUM(delta) >= 5} so it sees every
 *       movement in {@code personal_points_ledger}).</li>
 * </ul>
 *
 * <p>Standalone {@code @Component} (not a {@code @Repository} method)
 * because the SQL joins three tables and the
 * {@code RevivalEventRepository} interface is the wrong owner — the table
 * roots are room/friendship/ledger, not revival_events. Returns
 * {@code List<Long>} of user ids; the caller batches the
 * {@code userRepository.findAllById(...)} load itself to avoid N+1 inside
 * the listener.
 *
 * <p>Native query so the join shape is transparent; the partial unique
 * index on {@code friendships(requester_id, addressee_id)} (V8) makes
 * either-direction matching cheap.
 */
@Component
public class FriendGiftEligibilityQuery {

    static final int FRIEND_GIFT_COST = 5;

    @PersistenceContext
    private final EntityManager entityManager;

    public FriendGiftEligibilityQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Returns the giver user-ids eligible to friend-gift the receiver in
     * the given room. Order is non-deterministic; the caller does not
     * depend on stable order (the push fan-out is per-user-id, with
     * {@code notification_log} keyed by the elimination tuple — order is
     * irrelevant to correctness or dedup).
     *
     * <p>The SQL is parameterized via JPA's positional/named parameter
     * binding — never string interpolation (project-context Java security
     * rule).
     */
    public List<Long> findEligibleGiverUserIds(long roomId, long receiverUserId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = entityManager.createNativeQuery("""
                select rm.user_id
                from room_members rm
                join friendships f on (
                    (f.requester_id = rm.user_id and f.addressee_id = :receiverUserId)
                    or (f.requester_id = :receiverUserId and f.addressee_id = rm.user_id)
                ) and f.status = 'ACCEPTED'
                left join personal_points_ledger ppl on (
                    ppl.user_id = rm.user_id and ppl.room_id = :roomId
                )
                where rm.room_id = :roomId
                  and rm.user_id <> :receiverUserId
                group by rm.user_id
                having coalesce(sum(ppl.delta), 0) >= :cost
                """)
                .setParameter("roomId", roomId)
                .setParameter("receiverUserId", receiverUserId)
                .setParameter("cost", FRIEND_GIFT_COST)
                .getResultList();

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(Number::longValue).toList();
    }
}
