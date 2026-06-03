package com.yeosal.api.room;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Story 5.2 — leader-only immediate-effect transfer of the room's
 * leader-of-record. Atomically flips {@code rooms.owner_id} AND both
 * affected {@code room_members.role} rows inside a single
 * {@code @Transactional} boundary so partial-failure states (orphaned
 * roles, two OWNER rows, zero OWNER rows) are impossible.
 *
 * <p>Pre-conditions enforced in order so the FE sees the most precise error
 * the user can act on:
 * <ol>
 *   <li>Caller is the current leader → otherwise 403 FORBIDDEN.</li>
 *   <li>Self-transfer rejected → 400 VALIDATION ("이미 본인이 방장입니다.").</li>
 *   <li>Target user exists → otherwise 400 VALIDATION.</li>
 *   <li>Target is a member of the room → otherwise 400 VALIDATION.</li>
 *   <li>Target's survival_state is ACTIVE or YELLOW → otherwise 409
 *       INELIGIBLE_LEADER (covers RED, SPECTATOR, and the defensive
 *       missing-row case).</li>
 * </ol>
 *
 * <p>Realtime emission runs {@code afterCommit} so a rolled-back transfer
 * never lights up the fan-out. Broker failures are warn-and-swallowed in
 * {@link RealtimePublisher#publishLeadershipChange} so a STOMP hiccup
 * cannot retroactively roll back the committed transfer.
 */
@Service
public class TransferLeadershipService {

    private static final Logger log = LoggerFactory.getLogger(TransferLeadershipService.class);

    private final RoomService roomService;
    private final RoomMemberRepository roomMembers;
    private final SurvivalStateRepository survivalStates;
    private final UserRepository users;
    private final RealtimePublisher realtime;

    public TransferLeadershipService(
            RoomService roomService,
            RoomMemberRepository roomMembers,
            SurvivalStateRepository survivalStates,
            UserRepository users,
            RealtimePublisher realtime) {
        this.roomService = roomService;
        this.roomMembers = roomMembers;
        this.survivalStates = survivalStates;
        this.users = users;
        this.realtime = realtime;
    }

    @Transactional
    public RoomService.RoomSummary transferLeadership(User me, long roomId, long targetUserId) {
        Room room = roomService.requireRoomForUpdate(roomId);
        roomService.requireLeader(room, me);

        // Self-transfer check comes BEFORE the membership lookup so the
        // user gets the precise "본인이 방장" error instead of a successful
        // no-op pass-through.
        if (me.getId() == targetUserId) {
            throw new BadRequestException("이미 본인이 방장입니다.");
        }

        User target = users.findById(targetUserId)
                .orElseThrow(() -> new BadRequestException("대상 사용자를 찾을 수 없습니다."));

        RoomMember targetMember = roomMembers.findByRoomAndUser(room, target)
                .orElseThrow(() -> new BadRequestException("대상은 이 방의 멤버가 아닙니다."));

        SurvivalStatus targetStatus = survivalStates
                .findByRoomIdAndUserIdForUpdate(roomId, targetUserId)
                .map(SurvivalState::getStatus)
                .orElse(null);
        if (targetStatus != SurvivalStatus.ACTIVE && targetStatus != SurvivalStatus.YELLOW) {
            throw new IneligibleLeaderException("대상의 상태를 확인할 수 없어요.");
        }

        // The previous leader's membership MUST exist — defensive throw.
        RoomMember previousLeaderMember = roomMembers.findByRoomAndUser(room, me)
                .orElseThrow(() -> new IllegalStateException(
                        "leader membership missing for roomId=" + roomId + " userId=" + me.getId()));

        long previousLeaderUserId = me.getId();
        long newLeaderUserId = target.getId();

        room.setOwner(target);
        targetMember.setRole(RoomRole.OWNER);
        previousLeaderMember.setRole(RoomRole.MEMBER);
        // dirty-check on @Transactional commit flushes all three writes
        // together so partial-failure states cannot persist.

        LeadershipChangePayload payload = new LeadershipChangePayload(
                roomId,
                previousLeaderUserId,
                newLeaderUserId,
                "MANUAL_TRANSFER");
        registerAfterCommitPublish(roomId, payload);

        return RoomService.RoomSummary.from(room);
    }

    private void registerAfterCommitPublish(long roomId, LeadershipChangePayload payload) {
        Runnable publish = () -> {
            try {
                realtime.publishLeadershipChange(roomId, payload);
            } catch (RuntimeException ex) {
                log.warn("[realtime] LeadershipChange publish failed roomId={}: {}",
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
