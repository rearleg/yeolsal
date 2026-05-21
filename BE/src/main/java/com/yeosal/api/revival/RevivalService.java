package com.yeosal.api.revival;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateTransitionEvent;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-revival orchestrator for Story 3.1 (FR-8.3.1 + FR-8.3.2,
 * Architecture §4.4 + §4.5 + §4.6 + §4.12).
 *
 * <h2>Two-layer exactly-once defence (Architecture §4.4)</h2>
 *
 * <ol>
 *   <li><strong>Primary — Postgres advisory lock.</strong>
 *       {@code pg_advisory_xact_lock(hashtextextended("revival:{roomId}:
 *       {userId}:{eliminatedAtEpochMillis}", 0))}. The triple key matches
 *       the partial unique index's column tuple exactly. Held until the
 *       transaction commits or rolls back.</li>
 *   <li><strong>Secondary — partial unique index.</strong>
 *       {@code ux_revival_events_one_per_elimination
 *       (room_id, user_id, eliminated_at) WHERE succeeded = true} (V11
 *       lines 76-78). The native INSERT uses
 *       {@code ON CONFLICT DO NOTHING}; a zero-row return surfaces as
 *       {@link AlreadyRevivedException}. Any
 *       {@link DataIntegrityViolationException} that escapes (Hibernate
 *       flush past the service catch) is re-mapped to the same exception
 *       via {@link #isRevivalDedupConflict}.</li>
 * </ol>
 *
 * <h2>Free-ticket lifetime-one (Architecture §4.12)</h2>
 *
 * <p>The flag lives on {@code users}, not per-room. A user in N rooms who
 * uses the FREE_TICKET in any one of them consumes it forever; subsequent
 * FREE_TICKET attempts in any room throw
 * {@link FreeTicketAlreadyUsedException}. The atomic check-and-set is
 * essential — the advisory lock keys on the per-elimination tuple, which
 * doesn't cover concurrent FREE_TICKET attempts across different
 * eliminations.
 */
@Service
public class RevivalService {

    private static final Logger log = LoggerFactory.getLogger(RevivalService.class);

    static final short FREE_TICKET_POOL_DELTA = 5;
    static final short PERSONAL_POINTS_COST = 3;
    static final short PERSONAL_POINTS_POOL_DELTA = 3;
    /** Story 3.2 — donor pays 5 personal points; pool gains the same +5. */
    static final short FRIEND_GIFT_COST = 5;
    static final short FRIEND_GIFT_POOL_DELTA = 5;
    private static final String DEDUP_CONSTRAINT = "ux_revival_events_one_per_elimination";

    private final SurvivalStateRepository survivalStates;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final RevivalEventRepository revivalEvents;
    private final PersonalPointsLedgerRepository personalLedger;
    private final RoomPointPoolRepository roomPointPool;
    private final FriendshipRepository friendships;
    private final RoomMemberRepository roomMembers;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final Clock clock;

    public RevivalService(
            SurvivalStateRepository survivalStates,
            RoomRepository rooms,
            UserRepository users,
            RevivalEventRepository revivalEvents,
            PersonalPointsLedgerRepository personalLedger,
            RoomPointPoolRepository roomPointPool,
            FriendshipRepository friendships,
            RoomMemberRepository roomMembers,
            ApplicationEventPublisher eventPublisher,
            EntityManager entityManager,
            Clock clock) {
        this.survivalStates = survivalStates;
        this.rooms = rooms;
        this.users = users;
        this.revivalEvents = revivalEvents;
        this.personalLedger = personalLedger;
        this.roomPointPool = roomPointPool;
        this.friendships = friendships;
        this.roomMembers = roomMembers;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public RevivalEventDto reviveSelf(long roomId, long userId, RevivalSource source) {
        if (source == RevivalSource.FRIEND_GIFT) {
            throw new BadRequestException(
                    "source must be FREE_TICKET or PERSONAL_POINTS for self-revival");
        }

        SurvivalState state = survivalStates.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new NotFoundException("회생 대상 상태를 찾을 수 없습니다."));

        validatePreLockStatus(state);
        Instant eliminatedAt = loadEliminatedAt(state);

        acquireAdvisoryLock(roomId, userId, eliminatedAt.toEpochMilli());

        // Re-read after acquiring the lock — if the winner just committed
        // ACTIVE, we must observe it (Architecture §4.4 primary defence).
        entityManager.refresh(state);
        if (state.getStatus() == SurvivalStatus.ACTIVE) {
            throw new AlreadyRevivedException("이미 회생되었습니다.");
        }

        if (source == RevivalSource.FREE_TICKET) {
            int updated = users.markFreeTicketUsed(userId);
            if (updated == 0) {
                throw new FreeTicketAlreadyUsedException("이미 무료 회생권을 사용했어요.");
            }
        }

        short pointsSpent = 0;
        int poolDelta = FREE_TICKET_POOL_DELTA;
        if (source == RevivalSource.PERSONAL_POINTS) {
            // AC3 lifetime ordering — PERSONAL_POINTS is only allowed AFTER
            // the user has spent their lifetime free ticket. The FE CTA
            // already gates this via WalletPreview, but the BE must defend
            // direct API calls (project-context "never trust the FE").
            User user = users.findById(userId)
                    .orElseThrow(() -> new NotFoundException(
                            "회생 대상 사용자를 찾을 수 없습니다."));
            if (!user.isFreeRevivalTicketUsed()) {
                throw new BadRequestException("무료 회생권을 먼저 사용해주세요.");
            }
            Integer summed = personalLedger.sumDeltaByUserIdAndRoomId(userId, roomId);
            int balance = summed == null ? 0 : summed;
            if (balance < PERSONAL_POINTS_COST) {
                throw new InsufficientPointsException("개인 포인트가 부족합니다.");
            }
            pointsSpent = PERSONAL_POINTS_COST;
            poolDelta = PERSONAL_POINTS_POOL_DELTA;
        }

        RoomPointPool pool = roomPointPool.selectForUpdate(roomId)
                .orElseThrow(() -> new IllegalStateException(
                        "room_point_pool row missing for roomId=" + roomId));
        int newTotal = pool.getTotal() + poolDelta;
        Instant now = clock.instant();

        try {
            int inserted = revivalEvents.insertOnConflictDoNothing(
                    roomId, userId, /* giverUserId */ null,
                    source.name(), /* sourceSubtype */ null,
                    pointsSpent, newTotal, eliminatedAt, now);
            if (inserted == 0) {
                throw new AlreadyRevivedException("이미 회생되었습니다.");
            }
        } catch (DataIntegrityViolationException ex) {
            if (isRevivalDedupConflict(ex)) {
                throw new AlreadyRevivedException("이미 회생되었습니다.");
            }
            throw ex;
        }

        long revivalEventId = revivalEvents
                .findByRoomIdAndUserIdAndEliminatedAt(roomId, userId, eliminatedAt)
                .map(RevivalEvent::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "revival_events row missing after insert: roomId=" + roomId
                                + " userId=" + userId));

        if (source == RevivalSource.PERSONAL_POINTS) {
            personalLedger.save(new PersonalPointsLedger(
                    userId, roomId, (short) -PERSONAL_POINTS_COST,
                    LedgerReason.REVIVAL_SPEND, now, revivalEventId));
        }

        int updatedPool = roomPointPool.incrementTotal(roomId, poolDelta);
        if (updatedPool == 0) {
            throw new IllegalStateException(
                    "room_point_pool row vanished mid-transaction: roomId=" + roomId);
        }

        SurvivalStatus fromStatus = state.getStatus();
        state.markRevived(now);

        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        Long ownerUserId = room.getOwner().getId();

        eventPublisher.publishEvent(new SurvivalStateTransitionEvent(
                roomId, userId, ownerUserId,
                fromStatus, SurvivalStatus.ACTIVE,
                now, /* broadVisibilityAt */ null));
        eventPublisher.publishEvent(new PointPoolChangeEvent(
                roomId, poolDelta, newTotal, revivalEventId, now));

        if (log.isInfoEnabled()) {
            log.info("[revival] roomId={} userId={} source={} pointsSpent={} newPool={} eventId={}",
                    roomId, userId, source, pointsSpent, newTotal, revivalEventId);
        }
        return new RevivalEventDto(
                revivalEventId, source.name(), pointsSpent, newTotal, now);
    }

    /**
     * Story 3.2 — friend-gift orchestrator (FR-8.3.3, AC1 17-step sequence).
     *
     * <p>Shares the advisory-lock + partial-unique-index defence with
     * {@link #reviveSelf}: the lock key is byte-identical to the
     * self-revival key with {@code userId = targetUserId}, so a
     * self-revival attempt and a friend-gift attempt on the same
     * elimination contend on the SAME lock — the index treats them as
     * the same row tuple. Drift defeats the defence (CRITICAL note 1 in
     * the story file).
     *
     * <p>Throws (each mapped by {@code ApiExceptionHandler}):
     * <ul>
     *   <li>{@link ForbiddenException} — giver not a room member.</li>
     *   <li>{@link BadRequestException} — self-target.</li>
     *   <li>{@link NotFoundException} — target not a room member.</li>
     *   <li>{@link NotEliminatedException} — target status not in
     *       {RED, SPECTATOR} or {@code eliminated_at == null}.</li>
     *   <li>{@link SpectatorWriteForbiddenException} — giver is
     *       SPECTATOR in this room.</li>
     *   <li>{@link NotFriendsForGiftException} — no ACCEPTED friendship.</li>
     *   <li>{@link AlreadyRevivedException} — race loser (advisory lock
     *       or partial unique index conflict).</li>
     *   <li>{@link InsufficientGiftPointsException} — giver's balance
     *       below the 5-point threshold (checked inside the lock).</li>
     * </ul>
     */
    @Transactional
    public FriendGiftRevivalDto reviveFriend(
            long roomId,
            User giver,
            long targetUserId,
            RevivalSourceSubtype sourceSubtype) {
        long giverUserId = giver.getId();
        // Story 3.3 AC3 — null defaults to PUSH_INITIATED so Story 3.2 callers
        // that don't send the field stay non-null in revival_events (the
        // semantic contract is "every FRIEND_GIFT row carries a subtype").
        String resolvedSubtype = (sourceSubtype == null
                ? RevivalSourceSubtype.PUSH_INITIATED.name()
                : sourceSubtype.name());

        // (1) Cheap precheck — giver must be a room member. Mirrors the
        //     controller-layer precheck but defends direct service calls
        //     (tests, future internal callers).
        if (!roomMembers.existsByRoomIdAndUserId(roomId, giverUserId)) {
            throw new ForbiddenException("방 멤버만 회생권을 선물할 수 있어요.");
        }
        // (2) Self-target rejection.
        if (giverUserId == targetUserId) {
            throw new BadRequestException(
                    "자기 자신에게는 회생권을 선물할 수 없어요. 무료 회생권이나 개인 포인트를 사용해주세요.");
        }
        // (3) Target must also be a room member.
        if (!roomMembers.existsByRoomIdAndUserId(roomId, targetUserId)) {
            throw new NotFoundException("대상 멤버를 찾을 수 없어요.");
        }

        // (4) Target survival state — must be eliminated.
        SurvivalState targetState = survivalStates.findByRoomIdAndUserId(roomId, targetUserId)
                .orElseThrow(() -> new NotEliminatedException("회생 가능한 상태가 아닙니다."));
        SurvivalStatus targetPreStatus = targetState.getStatus();
        if (targetPreStatus != SurvivalStatus.RED && targetPreStatus != SurvivalStatus.SPECTATOR) {
            throw new NotEliminatedException("회생 가능한 상태가 아닙니다.");
        }
        Instant eliminatedAt = targetState.getEliminatedAt();
        if (eliminatedAt == null) {
            throw new NotEliminatedException("회생 가능한 상태가 아닙니다.");
        }

        // (5) Giver-spectator gate. Reuses the canonical 403 wire code.
        SurvivalState giverState = survivalStates
                .findByRoomIdAndUserId(roomId, giverUserId)
                .orElse(null);
        if (giverState != null && giverState.getStatus() == SurvivalStatus.SPECTATOR) {
            throw new SpectatorWriteForbiddenException();
        }

        // (6) Friendship gate — must have an ACCEPTED row in either direction.
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("대상 멤버를 찾을 수 없어요."));
        Optional<Friendship> friendship = friendships.findBetween(giver, target)
                .filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED);
        if (friendship.isEmpty()) {
            throw new NotFriendsForGiftException(
                    "친구가 된 멤버에게만 회생권을 선물할 수 있어요.");
        }

        // (7) Advisory-lock acquisition. CRITICAL — byte-identical key to
        //     reviveSelf with userId substituted by targetUserId so the
        //     two flows contend on the SAME lock for the same elimination.
        acquireAdvisoryLock(roomId, targetUserId, eliminatedAt.toEpochMilli());

        // (8) Re-read after the lock — the race winner may have committed
        //     between our pre-lock read and our lock acquisition.
        entityManager.refresh(targetState);
        if (targetState.getStatus() == SurvivalStatus.ACTIVE) {
            throw new AlreadyRevivedException("이미 회생되었습니다.");
        }

        // (9) Giver balance — checked INSIDE the lock so any in-flight
        //     concurrent debit (parallel friend-gift to a different
        //     target, parallel self-revival) is observed consistently.
        Integer summed = personalLedger.sumDeltaByUserIdAndRoomId(giverUserId, roomId);
        int balance = summed == null ? 0 : summed;
        if (balance < FRIEND_GIFT_COST) {
            throw new InsufficientGiftPointsException(
                    "회생권 선물에 필요한 포인트가 부족해요.");
        }

        // (10) SELECT room_point_pool FOR UPDATE — Architecture §4.6.
        RoomPointPool pool = roomPointPool.selectForUpdate(roomId)
                .orElseThrow(() -> new IllegalStateException(
                        "room_point_pool row missing for roomId=" + roomId));
        int newTotal = pool.getTotal() + FRIEND_GIFT_POOL_DELTA;
        Instant now = clock.instant();

        // (11) INSERT revival_events via the existing ON CONFLICT writer.
        try {
            int inserted = revivalEvents.insertOnConflictDoNothing(
                    roomId, targetUserId, giverUserId,
                    RevivalSource.FRIEND_GIFT.name(),
                    resolvedSubtype,
                    FRIEND_GIFT_COST, newTotal, eliminatedAt, now);
            if (inserted == 0) {
                throw new AlreadyRevivedException("이미 회생되었습니다.");
            }
        } catch (DataIntegrityViolationException ex) {
            if (isRevivalDedupConflict(ex)) {
                throw new AlreadyRevivedException("이미 회생되었습니다.");
            }
            throw ex;
        }

        // (12) Read back the just-inserted row id (FK target for ledger +
        //      payload for FriendGiftSentEvent).
        long revivalEventId = revivalEvents
                .findByRoomIdAndUserIdAndEliminatedAt(roomId, targetUserId, eliminatedAt)
                .map(RevivalEvent::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "revival_events row missing after insert: roomId=" + roomId
                                + " targetUserId=" + targetUserId));

        // (13) Append-only ledger row on the donor side.
        personalLedger.save(new PersonalPointsLedger(
                giverUserId, roomId, (short) -FRIEND_GIFT_COST,
                LedgerReason.FRIEND_GIFT_SPEND, now, revivalEventId));

        // (14) Bump the room pool.
        int updatedPool = roomPointPool.incrementTotal(roomId, FRIEND_GIFT_POOL_DELTA);
        if (updatedPool == 0) {
            throw new IllegalStateException(
                    "room_point_pool row vanished mid-transaction: roomId=" + roomId);
        }

        // (15) Flip the target's survival_state to ACTIVE.
        SurvivalStatus fromStatus = targetState.getStatus();
        targetState.markRevived(now);

        // (16) Lifetime-1 snapshot — AFTER the INSERT has committed in
        //      the same transaction (PostgreSQL sees its own writes), and
        //      BEFORE the event publish so the response DTO carries the
        //      precise boolean. The EXCLUDING-self clause is what makes
        //      the check race-free against the just-inserted row.
        boolean isFirstEverFriendGiftSend = !revivalEvents
                .existsFriendGiftSendByGiver(giverUserId, revivalEventId);

        // (17) Publish AFTER_COMMIT-driven fan-out events.
        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        Long ownerUserId = room.getOwner().getId();
        eventPublisher.publishEvent(new SurvivalStateTransitionEvent(
                roomId, targetUserId, ownerUserId,
                fromStatus, SurvivalStatus.ACTIVE,
                now, /* broadVisibilityAt */ null));
        eventPublisher.publishEvent(new PointPoolChangeEvent(
                roomId, FRIEND_GIFT_POOL_DELTA, newTotal, revivalEventId, now));
        eventPublisher.publishEvent(new FriendGiftSentEvent(
                roomId, giverUserId, targetUserId, revivalEventId, now));

        if (log.isInfoEnabled()) {
            log.info(
                    "[friend-gift] roomId={} giverUserId={} targetUserId={} pointsSpent={} newPool={} eventId={} lifetimeOne={}",
                    roomId, giverUserId, targetUserId, FRIEND_GIFT_COST, newTotal,
                    revivalEventId, isFirstEverFriendGiftSend);
        }

        return new FriendGiftRevivalDto(
                revivalEventId,
                RevivalSource.FRIEND_GIFT.name(),
                FRIEND_GIFT_COST,
                newTotal,
                now,
                isFirstEverFriendGiftSend,
                target.getNickname());
    }

    private static void validatePreLockStatus(SurvivalState state) {
        SurvivalStatus status = state.getStatus();
        if (status == SurvivalStatus.ACTIVE) {
            throw new AlreadyRevivedException("이미 회생되었습니다.");
        }
        if (status != SurvivalStatus.RED && status != SurvivalStatus.SPECTATOR) {
            throw new NotEliminatedException("회생 가능한 상태가 아닙니다.");
        }
    }

    private static Instant loadEliminatedAt(SurvivalState state) {
        Instant eliminatedAt = state.getEliminatedAt();
        if (eliminatedAt == null) {
            throw new NotEliminatedException("회생 가능한 상태가 아닙니다.");
        }
        return eliminatedAt;
    }

    /**
     * {@code pg_advisory_xact_lock(hashtextextended(text, 0))}. The
     * {@code bigint} return of {@code hashtextextended} matches the
     * {@code pg_advisory_xact_lock(bigint)} overload. The lock auto-
     * releases at transaction end (commit or rollback). The triple key
     * {@code revival:{roomId}:{userId}:{eliminatedAtEpochMillis}} matches
     * the partial unique index's column tuple — drift defeats the
     * defence (Architecture §4.4).
     */
    private void acquireAdvisoryLock(long roomId, long userId, long eliminatedAtMillis) {
        String key = "revival:" + roomId + ":" + userId + ":" + eliminatedAtMillis;
        entityManager
                .createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .setParameter("k", key)
                .getSingleResult();
    }

    /**
     * Architecture §4.4 service-layer catch path — discriminates the
     * revival partial-unique conflict from generic DB integrity
     * violations by inspecting the most-specific cause's message for
     * the constraint name. Returns {@code true} for the dedup hit.
     */
    private static boolean isRevivalDedupConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause == null) {
            return false;
        }
        String message = cause.getMessage();
        return message != null && message.contains(DEDUP_CONSTRAINT);
    }
}
