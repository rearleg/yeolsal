package com.yeosal.api.room;

import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateTransitionEvent;
import com.yeosal.api.survival.SurvivalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Subscriber to {@link SurvivalStateTransitionEvent}. When the room owner transitions to
 * {@link SurvivalStatus#RED}, the longest-tenured ACTIVE member is promoted
 * to leader-of-record: {@code rooms.owner_id} and both affected
 * {@code room_members.role} rows flip atomically inside a single
 * {@link Propagation#REQUIRES_NEW} transaction, and a
 * {@link LeadershipChangePayload} with {@code reason="AUTO_ELIMINATION"} is
 * registered for {@code afterCommit} fan-out.
 *
 * <p>Pattern compliance — mirrors {@code SurvivalStateRealtimeListener} and
 * {@code EligibleGiverPushListener}:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} so a rolled-back
 *       elimination NEVER lights up the promotion fan-out.</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} because Spring's AFTER_COMMIT
 *       phase leaves no outer transaction context, and the three writes
 *       (owner_id + both role flips) MUST commit together.</li>
 * </ul>
 *
 * <p>Race resolution vs manual transfer ({@link TransferLeadershipService}):
 * both writers go through
 * {@link RoomService#requireRoomForUpdate(long)}, so a 06:00 KST evaluator
 * tick that interleaves with a leader's last-second manual transfer is
 * serialised at the row-lock. The {@code currentOwner != previousLeader}
 * idempotency guard at the top of the promotion block makes a late-firing
 * auto path defer to whatever owner the manual flow wrote first.
 *
 * <p>Strict-ACTIVE eligibility (NOT ACTIVE union YELLOW): the manual flow
 * accepts YELLOW as a soft warning since a human is in the loop; the auto
 * flow has no human judgement and MUST pick the most-stable candidate so
 * leadership doesn't ratchet through two RED transitions in two days.
 */
@Component
public class AutoLeaderPromotionListener {

    private static final Logger log = LoggerFactory.getLogger(AutoLeaderPromotionListener.class);

    private final RoomService roomService;
    private final RoomMemberRepository roomMembers;
    private final SurvivalStateRepository survivalStates;
    private final RealtimePublisher realtime;

    public AutoLeaderPromotionListener(
            RoomService roomService,
            RoomMemberRepository roomMembers,
            SurvivalStateRepository survivalStates,
            RealtimePublisher realtime) {
        this.roomService = roomService;
        this.roomMembers = roomMembers;
        this.survivalStates = survivalStates;
        this.realtime = realtime;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(SurvivalStateTransitionEvent event) {
        // Cheap-first filter: enum compare before boxed owner id handling.
        if (event.toStatus() != SurvivalStatus.RED) {
            return;
        }
        Long ownerUserId = event.ownerUserId();
        if (ownerUserId == null || ownerUserId != event.userId()) {
            return;
        }

        long previousLeaderUserId = event.userId();
        long roomId = event.roomId();

        // Pessimistic room lock serialises against manual leadership transfer.
        // Side-effect: requireRoomForUpdate also runs lazy cap promotion;
        // beneficial here because the listener should use the freshest room state.
        Room room = roomService.requireRoomForUpdate(roomId);
        long currentOwnerId = room.getOwner().getId();
        if (currentOwnerId != previousLeaderUserId) {
            if (log.isInfoEnabled()) {
                log.info("[auto-leader] skip roomId={} previousLeaderUserId={} currentOwnerId={}"
                                + " - already changed",
                        roomId, previousLeaderUserId, currentOwnerId);
            }
            return;
        }

        // Strict-ACTIVE candidate selection; YELLOW is accepted only by the manual flow.
        RoomMember newLeader = roomMembers
                .findLongestTenuredActiveCandidates(roomId, previousLeaderUserId)
                .stream()
                .findFirst()
                .orElse(null);

        // Dormant room: no eligible replacement, so leadership stays unchanged.
        if (newLeader == null) {
            if (log.isInfoEnabled()) {
                log.info("[auto-leader] dormant roomId={} previousLeaderUserId={}"
                                + " - no ACTIVE candidates",
                        roomId, previousLeaderUserId);
            }
            return;
        }

        // Load previous-leader membership before setOwner.
        // After setOwner, room.getOwner() resolves to the NEW leader; doing
        // the lookup here would double-flip the new candidate's row to MEMBER.
        RoomMember previousLeaderMember = roomMembers
                .findByRoomAndUser(room, room.getOwner())
                .orElseThrow(() -> new IllegalStateException(
                        "leader membership missing for roomId=" + roomId
                                + " userId=" + previousLeaderUserId));

        long newLeaderUserId = newLeader.getUser().getId();
        RoomMember lockedNewLeader = roomMembers
                .findByRoomIdAndUserIdForUpdate(roomId, newLeaderUserId)
                .orElse(null);
        SurvivalStatus lockedCandidateStatus = survivalStates
                .findByRoomIdAndUserIdForUpdate(roomId, newLeaderUserId)
                .map(SurvivalState::getStatus)
                .orElse(null);
        if (lockedNewLeader == null || lockedCandidateStatus != SurvivalStatus.ACTIVE) {
            if (log.isInfoEnabled()) {
                log.info("[auto-leader] skip roomId={} candidateUserId={} status={} - no longer eligible",
                        roomId, newLeaderUserId, lockedCandidateStatus);
            }
            return;
        }

        room.setOwner(lockedNewLeader.getUser());
        lockedNewLeader.setRole(RoomRole.OWNER);
        previousLeaderMember.setRole(RoomRole.MEMBER);
        // dirty-check on the REQUIRES_NEW boundary flushes all three writes
        // together so partial-failure states cannot persist.

        // Publish after this transaction commits so rolled-back promotion
        // attempts never light up the fan-out.
        LeadershipChangePayload payload = new LeadershipChangePayload(
                roomId,
                previousLeaderUserId,
                newLeaderUserId,
                "AUTO_ELIMINATION");
        registerAfterCommitPublish(roomId, payload);

        if (log.isInfoEnabled()) {
            log.info("[auto-leader] promoted roomId={} previousLeaderUserId={} newLeaderUserId={}",
                    roomId, previousLeaderUserId, newLeaderUserId);
        }
    }

    private void registerAfterCommitPublish(long roomId, LeadershipChangePayload payload) {
        Runnable publish = () -> {
            try {
                realtime.publishLeadershipChange(roomId, payload);
            } catch (RuntimeException ex) {
                log.warn("[auto-leader] LeadershipChange publish failed roomId={}: {}",
                        roomId, ex.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
