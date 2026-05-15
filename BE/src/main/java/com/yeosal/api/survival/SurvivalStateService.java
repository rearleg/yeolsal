package com.yeosal.api.survival;

import com.fasterxml.jackson.databind.JsonNode;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationLogRepository;
import com.yeosal.api.revival.LedgerReason;
import com.yeosal.api.revival.PersonalPointsLedger;
import com.yeosal.api.revival.PersonalPointsLedgerRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Survival-state lifecycle service (Architecture §4.1, PRD FR-8.1.*).
 *
 * <h2>AC3/AC4 grace contract — DO NOT REGRESS (Story 1.1 → Story 1.2)</h2>
 *
 * <p>The {@link #inGraceWindow(SurvivalState, Instant)} guard is the sole
 * authorization for any {@code YELLOW → RED} transition:
 *
 * <ul>
 *   <li>If {@code inGraceWindow(state, now)} returns {@code true}, the only
 *       legal transition for a missed-rule day is {@code ACTIVE → YELLOW}.
 *       {@code YELLOW → RED} is forbidden while in grace.</li>
 *   <li>If {@code inGraceWindow(state, now)} returns {@code false} (legacy
 *       {@code grace_ends_at == null} rows or {@code now >= grace_ends_at}),
 *       the member is fully subject to the rolling 7-day window — Story 1.2
 *       may transition {@code YELLOW → RED}.</li>
 * </ul>
 *
 * <p>The boundary is <em>exclusive</em>: at the exact instant
 * {@code now == grace_ends_at}, the guard reports out-of-grace.
 *
 * <h2>Story 1.2 — {@link #evaluateRoom(long, LocalDate)}</h2>
 *
 * <p>One {@code @Transactional} method per room called by
 * {@code SurvivalStateEvaluatorJob} at 06:00 KST. Walks every member, runs
 * the {@code notification_log} dedup gate (per-user idempotency, AC8), then:
 *
 * <ul>
 *   <li><strong>Compliant</strong> — {@code daily_entries} row exists for
 *       the prior entry-date → insert {@code +2 SURVIVAL} to
 *       {@code personal_points_ledger} (AC3).</li>
 *   <li><strong>Miss + freeze unused this month</strong> — insert a
 *       {@code streak_freezes} row via {@code ON CONFLICT DO NOTHING}; no
 *       state change, no realtime emission (AC4).</li>
 *   <li><strong>Miss + freeze unavailable</strong> — drive the state
 *       machine: {@code ACTIVE → YELLOW} on first uncovered miss (AC5);
 *       {@code YELLOW → RED} on the second miss within the rolling 7-day
 *       window — clamped to YELLOW when {@link #inGraceWindow} fires
 *       (AC6).</li>
 * </ul>
 *
 * <p>All wall-clock reads come from the injected {@link Clock}; a single
 * {@code Instant now = clock.instant()} flows through the per-room call so
 * {@code last_state_change_at}, {@code eliminated_at}, and
 * {@code broad_visibility_at} share one snapshot (AC9). Transitions
 * publish {@link SurvivalStateTransitionEvent} via Spring's
 * {@link ApplicationEventPublisher}; the listener fires
 * {@code AFTER_COMMIT} so a rolled-back transaction never lights up the
 * realtime fan-out (Story 1.2 BE-6).
 */
@Service
public class SurvivalStateService {

    private static final Logger log = LoggerFactory.getLogger(SurvivalStateService.class);
    private static final Duration GRACE_WINDOW = Duration.ofDays(14);
    private static final Duration ROLLING_WINDOW = Duration.ofDays(7);
    private static final Duration RED_BROAD_DELAY = Duration.ofHours(24);
    private static final short SURVIVAL_DELTA = 2;

    private final SurvivalStateRepository repository;
    private final NotificationLogRepository notificationLogs;
    private final UserRepository users;
    private final StreakFreezeRepository streakFreezes;
    private final PersonalPointsLedgerRepository personalLedger;
    private final DailyEntryRepository dailyEntries;
    private final RoomMemberRepository roomMembers;
    private final RoomRepository rooms;
    private final RoomRuleVersionRepository ruleVersions;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SurvivalStateService(
            SurvivalStateRepository repository,
            NotificationLogRepository notificationLogs,
            UserRepository users,
            StreakFreezeRepository streakFreezes,
            PersonalPointsLedgerRepository personalLedger,
            DailyEntryRepository dailyEntries,
            RoomMemberRepository roomMembers,
            RoomRepository rooms,
            RoomRuleVersionRepository ruleVersions,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.repository = repository;
        this.notificationLogs = notificationLogs;
        this.users = users;
        this.streakFreezes = streakFreezes;
        this.personalLedger = personalLedger;
        this.dailyEntries = dailyEntries;
        this.roomMembers = roomMembers;
        this.rooms = rooms;
        this.ruleVersions = ruleVersions;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Mints the {@code survival_state} row for a fresh (room, user) join.
     * Status defaults to {@link SurvivalStatus#ACTIVE}; {@code grace_ends_at}
     * is {@code joinedAt + 14 days}.
     *
     * <p>Idempotent under the unique {@code (room_id, user_id)} race via the
     * V8/V9-style native {@code INSERT ... ON CONFLICT DO NOTHING} in
     * {@link SurvivalStateRepository#insertIfAbsent}.
     */
    @Transactional
    public SurvivalState initializeOnJoin(Room room, User user, Instant joinedAt) {
        Instant graceEndsAt = joinedAt.plus(GRACE_WINDOW);
        long roomId = room.getId();
        long userId = user.getId();
        repository.insertIfAbsent(roomId, userId, graceEndsAt);
        return repository
                .findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "survival_state row missing after upsert: roomId=" + roomId
                                + " userId=" + userId));
    }

    /**
     * Returns {@code true} iff the member is still inside the 14-day grace
     * window — i.e. the row carries a non-null {@code grace_ends_at} and
     * {@code now} is strictly before it. The boundary is exclusive so a
     * member whose grace ends exactly at {@code now} is already exposed to
     * Story 1.2's full transition rules.
     */
    public boolean inGraceWindow(SurvivalState state, Instant now) {
        Instant graceEndsAt = state.getGraceEndsAt();
        return graceEndsAt != null && now.isBefore(graceEndsAt);
    }

    /**
     * Evaluates every member of {@code roomId} against {@code priorEntryDate}
     * (already in {@code Asia/Seoul} via {@code EntryDateResolver}). One
     * transaction per room; misbehaving rooms surface up to the scheduler's
     * try/catch as a single failed-room count without aborting the night.
     *
     * <p>The {@code notification_log (user_id, 'SURVIVAL_STATE',
     * "{priorEntryDate}:{userId}")} gate is the per-user idempotency key
     * (AC3/AC8) — a re-run for the same {@code priorEntryDate} writes zero
     * additional ledger / state-machine rows.
     */
    @Transactional
    public EvaluationResult evaluateRoom(long roomId, LocalDate priorEntryDate) {
        Instant now = clock.instant();
        String monthKey = YearMonth.from(priorEntryDate).toString();

        // 1. Active rule for this room+month.
        RoomRuleVersion rule = ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        roomId, monthKey)
                .orElseThrow(() -> new IllegalStateException(
                        "rule version missing for roomId=" + roomId));

        // 2. Weekend skip — no progress, no miss, no notification row (AC2).
        JsonNode payload = rule.getRulePayload();
        if (!RulePresetEvaluator.shouldEvaluate(payload, priorEntryDate)) {
            return new EvaluationResult(roomId, priorEntryDate, 0, 0, 0, 0, 0, 0);
        }

        // 3. Room + members + entries.
        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new IllegalStateException(
                        "room missing for roomId=" + roomId));
        long ownerUserId = room.getOwner().getId();

        List<RoomMember> members = roomMembers.findByRoom(room);
        if (members.isEmpty()) {
            return new EvaluationResult(roomId, priorEntryDate, 0, 0, 0, 0, 0, 0);
        }

        List<User> memberUsers = members.stream().map(RoomMember::getUser).toList();
        List<DailyEntry> entries = dailyEntries.findByUserInAndDate(memberUsers, priorEntryDate);
        Set<Long> compliantUserIds = new HashSet<>();
        for (DailyEntry e : entries) {
            compliantUserIds.add(e.getUser().getId());
        }

        int evaluated = 0;
        int compliant = 0;
        int frozen = 0;
        int toYellow = 0;
        int toRed = 0;
        int skipped = 0;

        for (RoomMember rm : members) {
            long userId = rm.getUser().getId();
            evaluated += 1;

            // 4. Per-(room, user) dedup gate (AC3 + AC8 idempotency).
            String dedupKey = priorEntryDate + ":" + roomId + ":" + userId;
            int inserted = notificationLogs.insertIfAbsent(
                    userId, NotificationKind.SURVIVAL_STATE.name(), dedupKey);
            if (inserted == 0) {
                skipped += 1;
                continue;
            }

            if (compliantUserIds.contains(userId)) {
                // 5. Compliant path → +2 SURVIVAL (AC3).
                personalLedger.save(new PersonalPointsLedger(
                        userId, roomId, SURVIVAL_DELTA, LedgerReason.SURVIVAL, now));
                compliant += 1;
                continue;
            }

            // 6. Miss path → try freeze first (AC4).
            int freezeInserted = streakFreezes.insertIfAbsent(
                    userId, roomId, priorEntryDate, monthKey);
            if (freezeInserted == 1) {
                frozen += 1;
                continue;
            }

            // 7. Miss + no freeze → state machine.
            SurvivalState state = repository.findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "survival_state missing for roomId=" + roomId
                                    + " userId=" + userId));

            switch (state.getStatus()) {
                case ACTIVE -> {
                    state.setStatus(SurvivalStatus.YELLOW);
                    state.setLastStateChangeAt(now);
                    eventPublisher.publishEvent(new SurvivalStateTransitionEvent(
                            roomId, userId, ownerUserId,
                            SurvivalStatus.ACTIVE, SurvivalStatus.YELLOW,
                            now, null));
                    toYellow += 1;
                }
                case YELLOW -> {
                    Instant rollingFloor = now.minus(ROLLING_WINDOW);
                    boolean withinWindow = !state.getLastStateChangeAt().isBefore(rollingFloor);
                    if (!withinWindow) {
                        skipped += 1;
                        break;
                    }
                    if (inGraceWindow(state, now)) {
                        skipped += 1;
                        break;
                    }
                    Instant broadVisibilityAt = now.plus(RED_BROAD_DELAY);
                    state.setStatus(SurvivalStatus.RED);
                    state.setLastStateChangeAt(now);
                    state.setEliminatedAt(now);
                    state.setBroadVisibilityAt(broadVisibilityAt);
                    eventPublisher.publishEvent(new SurvivalStateTransitionEvent(
                            roomId, userId, ownerUserId,
                            SurvivalStatus.YELLOW, SurvivalStatus.RED,
                            now, broadVisibilityAt));
                    toRed += 1;
                }
                case RED, SPECTATOR -> skipped += 1;
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[evaluator] room={} date={} evaluated={} compliant={} frozen={} "
                            + "toYellow={} toRed={} skipped={}",
                    roomId, priorEntryDate, evaluated, compliant, frozen,
                    toYellow, toRed, skipped);
        }

        // UserRepository is injected for the Story 1.3 hand-off; the evaluator
        // itself does not resolve managed User entities, but constructor
        // shaping stays stable across stories. Silence unused warning.
        if (users == null) {
            throw new IllegalStateException("users repository missing");
        }

        return new EvaluationResult(
                roomId, priorEntryDate, evaluated, compliant, frozen, toYellow, toRed, skipped);
    }

    /**
     * Cold-load companion to the STOMP fan-out (Story 1.3, Architecture
     * §4.7 + §4.14). Returns one DTO per room member with the privacy
     * filter from AC3 applied server-side — a RED row inside its 24h
     * cooldown is reported as {@link SurvivalStatus#ACTIVE} with all
     * three timestamps nulled unless the viewer is the leader or the
     * affected user.
     *
     * <p>Query budget (AC10): four SQL statements regardless of member
     * count — {@code rooms.findById}, {@code roomMembers.existsByRoomIdAndUserId},
     * {@code roomMembers.findByRoom}, {@code survivalStates.findByRoomId}.
     * The owner association is touched inside this transaction so the
     * lazy load happens before serialization (project-context
     * {@code open-in-view: false}).
     *
     * @throws NotFoundException  when the room does not exist.
     * @throws ForbiddenException when the viewer is not a member.
     */
    @Transactional(readOnly = true)
    public List<SurvivalStateDto> roster(long roomId, long viewerUserId) {
        Instant now = clock.instant();

        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        // Force the lazy owner load inside the @Transactional boundary
        // (project-context open-in-view: false). The leader bypass below
        // depends on this id, but even if it didn't, deferring the load
        // would risk LazyInitializationException at Jackson time.
        long ownerUserId = room.getOwner().getId();

        if (!roomMembers.existsByRoomIdAndUserId(roomId, viewerUserId)) {
            throw new ForbiddenException("방 멤버만 접근할 수 있습니다.");
        }

        boolean viewerIsLeader = ownerUserId == viewerUserId;
        // Fetch-join variants collapse the per-member user lazy loads that
        // the plain finders would trigger inside the shaping loop below
        // (Story 1.3 AC10 four-SQL budget — Epic 1 retro T7).
        List<RoomMember> members = roomMembers.findByRoomFetchingUser(room);
        Map<Long, SurvivalState> byUserId = new HashMap<>();
        for (SurvivalState s : repository.findByRoomIdFetchingUser(roomId)) {
            byUserId.put(s.getUser().getId(), s);
        }

        List<SurvivalStateDto> rows = new ArrayList<>(members.size());
        for (RoomMember rm : members) {
            User u = rm.getUser();
            SurvivalState state = byUserId.get(u.getId());
            rows.add(toDto(u, state, viewerUserId, viewerIsLeader, now));
        }
        return rows;
    }

    /**
     * Build one {@link SurvivalStateDto} for {@code user}, applying the
     * AC3 privacy mask when all five conjuncts hold.
     */
    private SurvivalStateDto toDto(
            User user,
            SurvivalState state,
            long viewerUserId,
            boolean viewerIsLeader,
            Instant now) {
        if (state == null) {
            // Defensive — V11 backfill should have created every row, but
            // a missing one surfaces as ACTIVE-by-default rather than NPE.
            return new SurvivalStateDto(
                    user.getId(), user.getNickname(), SurvivalStatus.ACTIVE,
                    null, null, null);
        }

        boolean masked = state.getStatus() == SurvivalStatus.RED
                && state.getBroadVisibilityAt() != null
                && state.getBroadVisibilityAt().isAfter(now)
                && viewerUserId != state.getUser().getId()
                && !viewerIsLeader;

        if (masked) {
            return new SurvivalStateDto(
                    user.getId(), user.getNickname(), SurvivalStatus.ACTIVE,
                    null, null, null);
        }
        return new SurvivalStateDto(
                user.getId(),
                user.getNickname(),
                state.getStatus(),
                state.getLastStateChangeAt(),
                state.getEliminatedAt(),
                state.getBroadVisibilityAt());
    }

    /**
     * Per-room counts surfaced to the scheduler for aggregation + the
     * mass-elimination alarm threshold (AC10/AC11).
     */
    public record EvaluationResult(
            long roomId,
            LocalDate date,
            int evaluated,
            int compliant,
            int frozen,
            int toYellow,
            int toRed,
            int skipped) {}
}
