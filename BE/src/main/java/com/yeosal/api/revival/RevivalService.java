package com.yeosal.api.revival;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.room.Room;
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
    private static final String DEDUP_CONSTRAINT = "ux_revival_events_one_per_elimination";

    private final SurvivalStateRepository survivalStates;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final RevivalEventRepository revivalEvents;
    private final PersonalPointsLedgerRepository personalLedger;
    private final RoomPointPoolRepository roomPointPool;
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
            ApplicationEventPublisher eventPublisher,
            EntityManager entityManager,
            Clock clock) {
        this.survivalStates = survivalStates;
        this.rooms = rooms;
        this.users = users;
        this.revivalEvents = revivalEvents;
        this.personalLedger = personalLedger;
        this.roomPointPool = roomPointPool;
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
