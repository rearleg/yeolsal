package com.yeosal.api.room;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.daily.TodoItem;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.revival.RoomPointPool;
import com.yeosal.api.revival.RoomPointPoolRepository;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.survival.RecordVisibilityPref;
import com.yeosal.api.survival.RecordVisibilityPrefRepository;
import com.yeosal.api.survival.RoomRuleVersionRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final RoomInviteRepository roomInvites;
    private final GroupMemberMinimumRepository minimums;
    private final GroupWarningRepository warnings;
    private final UserRepository users;
    private final DailyService dailyService;
    private final DailyEntryRepository dailyEntries;
    private final ChatService chatService;
    private final InviteCodeGenerator codeGenerator;
    private final Clock clock;
    private final com.yeosal.api.realtime.RealtimePublisher realtime;
    private final SurvivalStateService survivalState;
    private final SurvivalStateRepository survivalStates;
    private final RecordVisibilityPrefRepository visibilityPrefs;
    private final RoomPointPoolRepository roomPointPool;
    private final RoomRuleVersionRepository roomRuleVersions;
    private final RoomCapPromotionService capPromotion;
    private final EntityManager entityManager;

    /** Mirrors {@code FriendService.STREAK_WINDOW_DAYS} so the per-member streak
     * displayed in the group dashboard agrees with what each member sees on
     * their own profile screen. */
    private static final int STREAK_WINDOW_DAYS = 365;

    public RoomService(
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            RoomInviteRepository roomInvites,
            GroupMemberMinimumRepository minimums,
            GroupWarningRepository warnings,
            UserRepository users,
            DailyService dailyService,
            DailyEntryRepository dailyEntries,
            ChatService chatService,
            InviteCodeGenerator codeGenerator,
            Clock clock,
            com.yeosal.api.realtime.RealtimePublisher realtime,
            SurvivalStateService survivalState,
            SurvivalStateRepository survivalStates,
            RecordVisibilityPrefRepository visibilityPrefs,
            RoomPointPoolRepository roomPointPool,
            RoomRuleVersionRepository roomRuleVersions,
            RoomCapPromotionService capPromotion,
            EntityManager entityManager
    ) {
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.roomInvites = roomInvites;
        this.minimums = minimums;
        this.warnings = warnings;
        this.users = users;
        this.dailyService = dailyService;
        this.dailyEntries = dailyEntries;
        this.chatService = chatService;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
        this.realtime = realtime;
        this.survivalState = survivalState;
        this.survivalStates = survivalStates;
        this.visibilityPrefs = visibilityPrefs;
        this.roomPointPool = roomPointPool;
        this.roomRuleVersions = roomRuleVersions;
        this.capPromotion = capPromotion;
        this.entityManager = entityManager;
    }

    /** Default room capacity per FR-8.1.1 (V11 widened range is [2, 30]). */
    static final int DEFAULT_MAX_MEMBERS = 12;

    /** Backwards-compatible overload — defaults to {@link GoalMinimumDays#DEFAULT} + max 12. */
    public RoomSummary create(User owner, String name) {
        return create(owner, name, GoalMinimumDays.DEFAULT, DEFAULT_MAX_MEMBERS);
    }

    /** Backwards-compatible overload — defaults to max 12 members. */
    public RoomSummary create(User owner, String name, int minDailyGoalDays) {
        return create(owner, name, minDailyGoalDays, DEFAULT_MAX_MEMBERS);
    }

    @Transactional
    public RoomSummary create(User owner, String name, int minDailyGoalDays, int maxMembers) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("방 이름은 비어있을 수 없습니다.");
        }
        // Whitelist check runs on the full int so out-of-range values
        // (including ones that would alias to an allowed short) are rejected.
        if (!GoalMinimumDays.isAllowed(minDailyGoalDays)) {
            throw new BadRequestException("최소 목표일수는 10/15/20/매일 중 하나여야 합니다.");
        }
        // FR-8.1.1: capacity is bounded server-side so a client that bypasses
        // the picker (older bundle, direct curl) still gets a clean 400 rather
        // than the JPA prePersist clamp swallowing the choice silently.
        if (maxMembers < 2 || maxMembers > 30) {
            throw new BadRequestException("정원은 2에서 30 사이여야 합니다.");
        }
        short min = (short) minDailyGoalDays;
        Room candidate = new Room(name.trim(), owner, min);
        candidate.setMaxMembers((short) maxMembers);
        Room room = rooms.save(candidate);
        Instant now = clock.instant();
        roomRuleVersions.insertDefaultIfAbsent(
                room.getId(),
                YearMonth.from(LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul"))).toString(),
                owner.getId());
        // Story 3.1 — seed the per-room point-pool counter cache. V11 step 15
        // backfills existing rooms once at migration time, but fresh rooms
        // created via this method need their pool row minted explicitly so
        // `RevivalService.selectForUpdate(roomId)` finds it.
        roomPointPool.save(new RoomPointPool(room.getId(), 0));
        // AC2 — bind joined_at to the injected Clock so survival_state.grace_ends_at
        // is derived from the SAME instant as the persisted RoomMember.joined_at,
        // not from a separate Instant.now() call inside prePersist.
        RoomMember ownerMember = new RoomMember(room, owner, RoomRole.OWNER);
        ownerMember.setJoinedAt(now);
        RoomMember savedOwnerMember = roomMembers.save(ownerMember);
        // Owner's per-member row mirrors the room minimum at creation time.
        minimums.save(new GroupMemberMinimum(room.getId(), owner.getId(), min));
        // AC2/AC7 — survival_state row is created atomically with the
        // RoomMember row inside this @Transactional method; idempotency under
        // a unique-(room, user) race lives in SurvivalStateService (native upsert).
        survivalState.initializeOnJoin(room, owner, savedOwnerMember.getJoinedAt());
        return RoomSummary.from(room);
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> myRooms(User user) {
        return roomMembers.findByUser(user).stream()
                .map(RoomMember::getRoom)
                .map(room -> requireRoom(room.getId()))
                .map(RoomSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberSummary> members(User viewer, long roomId) {
        Room room = requireRoom(roomId);
        requireMembership(room, viewer);
        // Batch-load every minimum row for this room so we don't issue one query
        // per member while building the response.
        Map<Long, GroupMemberMinimum> byUserId = new HashMap<>();
        for (GroupMemberMinimum m : minimums.findByRoomId(room.getId())) {
            byUserId.put(m.getUserId(), m);
        }
        // Story 5.2 — batch-load survival_state per (room, user) so the FE
        // leader-transfer picker can filter eligible members without a
        // second roundtrip. Members without a survival_state row (defensive
        // path — V11 backfill + initializeOnJoin should keep coverage 100%)
        // surface a null status to the wire.
        Instant now = clock.instant();
        boolean viewerIsLeader = room.getOwner().getId().equals(viewer.getId());
        Map<Long, SurvivalStatus> statusByUserId = new HashMap<>();
        for (SurvivalState s : survivalStates.findByRoomIdFetchingUser(roomId)) {
            statusByUserId.put(
                    s.getUser().getId(),
                    visibleSurvivalStatus(s, viewer.getId(), viewerIsLeader, now));
        }
        return roomMembers.findByRoom(room).stream()
                .map(rm -> MemberSummary.from(
                        rm,
                        byUserId.get(rm.getUser().getId()),
                        statusByUserId.get(rm.getUser().getId())))
                .toList();
    }

    /**
     * Per-member daily snapshot for the group detail dashboard. Mirrors the
     * batched-IO pattern in {@code FriendService.dailyFeed}: one query for
     * everyone's today entry, one for the streak window, then we group in
     * memory — never N+1 across members.
     *
     * <p>Returns one row per member of {@code roomId}; if a member hasn't
     * created today's entry yet the row reports empty progress (no goal /
     * 0 todos / no reflection) but still carries the member's identity so
     * the FE can render a "오늘 시작 안 함" placeholder.
     */
    @Transactional(readOnly = true)
    public List<MemberTodayDto> todayForRoom(User viewer, long roomId, LocalDate date) {
        if (date == null) {
            throw new BadRequestException("date 파라미터가 필요합니다.");
        }
        Room room = requireRoom(roomId);
        requireMembership(room, viewer);

        List<RoomMember> members = roomMembers.findByRoom(room);
        if (members.isEmpty()) {
            return List.of();
        }
        List<User> memberUsers = members.stream().map(RoomMember::getUser).toList();

        // Batch: one query for everyone's entry on `date`, one for the
        // streak window. The streak window mirrors the friend-feed window so
        // a member's group-page streak agrees with their friend-feed streak.
        List<DailyEntry> todayEntries = dailyEntries.findByUserInAndDate(memberUsers, date);
        Map<Long, DailyEntry> todayByUserId = todayEntries.stream()
                .collect(Collectors.toMap(e -> e.getUser().getId(), e -> e, (a, b) -> a));

        LocalDate streakFrom = date.minusDays(STREAK_WINDOW_DAYS - 1L);
        List<DailyEntry> streakEntries = dailyEntries.findGrassEntriesByUsersBetween(
                memberUsers, streakFrom, date);
        Map<Long, List<DailyEntry>> streakByUserId = streakEntries.stream()
                .collect(Collectors.groupingBy(e -> e.getUser().getId()));

        // Story 2.3 AC3 — room-scoped redaction. For each member, if the
        // viewer is not the member and the member is SPECTATOR in THIS room
        // without an opt-in row, scrub the daily/todo-derived fields. Batch
        // both reads so we don't issue 2 queries per member.
        Map<Long, SurvivalStatus> statusByUserId = new HashMap<>();
        for (SurvivalState s : survivalStates.findByRoomId(roomId)) {
            statusByUserId.put(s.getUser().getId(), s.getStatus());
        }
        Map<Long, Boolean> shareByUserId = new HashMap<>();
        for (User u : memberUsers) {
            visibilityPrefs
                    .findByUserIdAndRoomId(u.getId(), roomId)
                    .map(RecordVisibilityPref::isShareOnElimination)
                    .ifPresent(share -> shareByUserId.put(u.getId(), share));
        }

        return members.stream()
                .map(rm -> {
                    User u = rm.getUser();
                    DailyEntry entry = todayByUserId.get(u.getId());
                    List<DailyEntry> streakRows = streakByUserId.getOrDefault(u.getId(), List.of());
                    List<GrassDay> window = dailyService.grassFromEntries(
                            u, streakFrom, date, streakRows);
                    int streak = dailyService.streakFromGrass(date, window);
                    boolean redact = !u.getId().equals(viewer.getId())
                            && statusByUserId.get(u.getId()) == SurvivalStatus.SPECTATOR
                            && !Boolean.TRUE.equals(shareByUserId.get(u.getId()));
                    if (redact) {
                        // Self-name + date stay; every record-derived field is
                        // suppressed identically to the "no entry yet" path so
                        // the FE cannot distinguish "redacted" from "not yet
                        // started" — NFR-9.3.2 forbids an explicit redaction
                        // flag in the wire shape.
                        return new MemberTodayDto(
                                u.getId(), u.getNickname(), date,
                                "", false, 0, false, 0);
                    }
                    if (entry == null) {
                        return new MemberTodayDto(
                                u.getId(), u.getNickname(), date,
                                "", false, 0, false, streak);
                    }
                    String goal = entry.getGoal() == null ? "" : entry.getGoal();
                    boolean goalSet = !goal.isBlank();
                    int completed = (int) entry.getTodos().stream()
                            .filter(TodoItem::isCompleted)
                            .count();
                    // A reflection row only exists once the user submits it
                    // (see DailyService.createReflection); submitted_at is
                    // NOT NULL by schema, so existence == submitted.
                    boolean reflected = entry.getReflection() != null;
                    return new MemberTodayDto(
                            u.getId(), u.getNickname(), date,
                            goal, goalSet, completed, reflected, streak);
                })
                .toList();
    }

    @Transactional
    public InviteSummary createInvite(User creator, long roomId, Duration ttl) {
        Room room = requireRoom(roomId);
        requireMembership(room, creator);

        String code = codeGenerator.generate(roomInvites::existsByCodeAndRevokedAtIsNull);
        Instant expiresAt = ttl == null ? null : clock.instant().plus(ttl);
        RoomInvite saved = roomInvites.save(new RoomInvite(room, code, creator, expiresAt));
        return InviteSummary.from(saved);
    }

    @Transactional
    public MemberSummary joinByCode(User user, String code) {
        Instant now = clock.instant();
        RoomInvite invite = roomInvites.findActiveByCode(code, now)
                .orElseThrow(() -> new NotFoundException("초대 코드를 찾을 수 없습니다."));
        Room room = requireRoom(invite.getRoom().getId());

        Optional<RoomMember> existing = roomMembers.findByRoomAndUser(room, user);
        if (existing.isPresent()) {
            GroupMemberMinimum existingMin =
                    minimums.findByRoomIdAndUserId(room.getId(), user.getId()).orElse(null);
            return MemberSummary.from(existing.get(), existingMin);
        }

        long memberCount = roomMembers.countByRoom(room);
        if (memberCount >= room.getMaxMembers()) {
            throw new BadRequestException("방 정원을 초과했습니다.");
        }
        // AC2 — anchor the new member's joined_at to the same Clock instant
        // we'll feed into initializeOnJoin below, so survival_state.grace_ends_at
        // equals joined_at + 14d exactly (no millisecond drift from prePersist).
        RoomMember newMember = new RoomMember(room, user, RoomRole.MEMBER);
        newMember.setJoinedAt(now);
        RoomMember saved = roomMembers.save(newMember);
        // New members start by mirroring the room's current minimum. They can
        // raise (but not lower) their own minimum later via the PATCH endpoint
        // introduced in PR E.
        GroupMemberMinimum minimum = minimums.save(new GroupMemberMinimum(
                room.getId(), user.getId(), room.getMinDailyGoalDays()));
        // AC2/AC7 — survival_state lives inside the same @Transactional
        // boundary as the membership write so a join is atomic across both
        // tables. Idempotency under a unique-(room, user) race lives in
        // SurvivalStateService (native INSERT ... ON CONFLICT DO NOTHING).
        survivalState.initializeOnJoin(room, user, saved.getJoinedAt());
        MemberSummary summary = MemberSummary.from(saved, minimum);
        // Realtime fan-out — every existing member subscribed to
        // /topic/rooms.{id}.members sees the new member without waiting
        // for a manual refresh. Publisher swallows broker errors so a
        // WS hiccup never rolls back the join itself.
        realtime.publishMemberAdded(room.getId(), summary);
        // Story 1.6 AC5 — publish a SYSTEM chat row "{nickname} 함께합니다 🌿"
        // so the J0 leader's WelcomeWindow can transition from solo→growing
        // and historical members see the join on chat reload. The hook reuses
        // ChatService.publishSystem (REQUIRES_NEW) so a chat-write failure
        // does NOT roll back the membership insert.
        chatService.publishMemberJoinedSystemMessage(room, user);
        return summary;
    }

    /**
     * Member-driven raise of their per-(room, user) minimum. The new value
     * must be in the global whitelist {@link GoalMinimumDays#ALLOWED} *and*
     * at least the room's current minimum — members can never set themselves
     * below the room-wide floor. Lowering one's own minimum below the floor
     * would defeat the room's social contract; raising is fine and intended.
     */
    @Transactional
    public MemberSummary updateMyMinimum(User user, long roomId, int minDailyGoalDays) {
        Room room = requireRoom(roomId);
        RoomMember membership = requireMembership(room, user);
        if (!GoalMinimumDays.isAllowed(minDailyGoalDays)) {
            throw new BadRequestException("최소 목표일수는 10/15/20/매일 중 하나여야 합니다.");
        }
        if (minDailyGoalDays < room.getMinDailyGoalDays()) {
            throw new BadRequestException(
                    "그룹 최소 기준(" + room.getMinDailyGoalDays() + "일) 이상으로만 설정할 수 있습니다.");
        }
        GroupMemberMinimum minimum = minimums
                .findByRoomIdAndUserId(room.getId(), user.getId())
                .orElseGet(() -> {
                    // Race-safe lazy create — concurrent PATCHes for a missing
                    // row would otherwise collide on the UNIQUE constraint.
                    minimums.insertIfAbsent(
                            room.getId(), user.getId(), room.getMinDailyGoalDays());
                    return minimums.findByRoomIdAndUserId(room.getId(), user.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "group_member_minimums row missing after upsert"));
                });
        minimum.setMinDailyGoalDays((short) minDailyGoalDays);
        // No explicit save — JPA dirty-check on the @Transactional commit. The
        // entity's @PreUpdate touches updated_at.
        return MemberSummary.from(membership, minimum);
    }

    @Transactional
    public void leave(User user, long roomId) {
        Room room = requireRoom(roomId);
        RoomMember membership = roomMembers.findByRoomAndUser(room, user)
                .orElseThrow(() -> new NotFoundException("방 멤버가 아닙니다."));

        if (membership.getRole() == RoomRole.OWNER) {
            long count = roomMembers.countByRoom(room);
            if (count > 1) {
                throw new BadRequestException(
                        "owner는 다른 멤버가 남아있는 동안 방을 떠날 수 없습니다.");
            }
            // group_member_minimums(room_id, user_id) FK has ON DELETE CASCADE
            // against room_members, so deleting the last RoomMember reaps the
            // matching minimum row automatically.
            roomMembers.delete(membership);
            rooms.delete(room);
            return;
        }
        roomMembers.delete(membership);
    }

    /**
     * Room loader + lazy cap promoter. Loads {@code roomId} (404 {@code NOT_FOUND}
     * on absence) and, before returning, asks {@link RoomCapPromotionService}
     * to flush any pending member-cap edit whose
     * {@code effective_from_month <= currentMonth(KST)} into {@code max_members}.
     * Promotion runs in a {@code REQUIRES_NEW} writable transaction so
     * readOnly callers (e.g. {@link #myRooms}, {@link #members},
     * {@link #todayForRoom}) can call this helper safely without tripping
     * Hibernate's readOnly flush guard. Promotion is idempotent — re-entrancy
     * is a no-op once the pending columns are cleared. Every leader-edited
     * next-month-only attribute (cap today; future minDays, etc.) MUST hook
     * its promotion into this single helper so the contract-integrity
     * contract cannot drift between callers.
     *
     * @throws NotFoundException when no row exists for {@code roomId}.
     */
    public Room requireRoom(long roomId) {
        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (capPromotion != null) {
            boolean promoted = capPromotion.promotePendingCapIfDue(room.getId());
            if (promoted) {
                // REQUIRES_NEW committed in a separate persistence context.
                // Refresh the already-managed entity rather than calling
                // findById again; JPA is allowed to return the stale first-level
                // cache instance for a second find.
                entityManager.refresh(room);
            }
        }
        return room;
    }

    public Room requireRoomForUpdate(long roomId) {
        requireRoom(roomId);
        return rooms.findByIdForUpdate(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
    }

    private SurvivalStatus visibleSurvivalStatus(
            SurvivalState state, long viewerUserId, boolean viewerIsLeader, Instant now) {
        boolean masked = state.getStatus() == SurvivalStatus.RED
                && state.getBroadVisibilityAt() != null
                && state.getBroadVisibilityAt().isAfter(now)
                && viewerUserId != state.getUser().getId()
                && !viewerIsLeader;
        return masked ? SurvivalStatus.ACTIVE : state.getStatus();
    }

    private RoomMember requireMembership(Room room, User user) {
        return roomMembers.findByRoomAndUser(room, user)
                .orElseThrow(() -> new ForbiddenException("방 멤버만 접근할 수 있습니다."));
    }

    /**
     * Leader-of-record authorization gate per FR-8.5.1. The {@code Room.owner}
     * FK is the canonical leader identity; this is the single source of truth
     * every leader-only endpoint (Stories 5.1, 5.2, 5.6) must consult so the
     * auth contract cannot drift between handlers.
     *
     * @throws ForbiddenException when {@code user} is not the room's owner.
     */
    public void requireLeader(Room room, User user) {
        if (!room.getOwner().getId().equals(user.getId())) {
            throw new ForbiddenException("방장 권한이 필요합니다.");
        }
    }

    public record RoomSummary(
            long id,
            String name,
            long ownerId,
            int maxMembers,
            int minDailyGoalDays,
            Instant createdAt,
            // Story 5.2 — nullable pending member-cap edit (next-month-only).
            // Both fields are non-null together or null together; the DB
            // CHECK chk_rooms_pending_cap_consistency keeps the paired-state
            // invariant on disk.
            Integer pendingMaxMembers,
            String pendingMaxMembersEffectiveFromMonth
    ) {
        public static RoomSummary from(Room room) {
            return new RoomSummary(
                    room.getId(),
                    room.getName(),
                    room.getOwner().getId(),
                    room.getMaxMembers(),
                    room.getMinDailyGoalDays(),
                    room.getCreatedAt(),
                    room.getPendingMaxMembers() == null
                            ? null
                            : (int) room.getPendingMaxMembers().shortValue(),
                    room.getPendingMaxMembersEffectiveFromMonth()
            );
        }
    }

    public record MemberSummary(
            long roomId,
            long userId,
            String nickname,
            RoomRole role,
            int currentMinimum,
            int warningCount,
            // Story 5.2 — nullable per-(room, user) survival status so the FE
            // leader-transfer picker can filter eligible candidates (ACTIVE
            // and YELLOW only; RED and SPECTATOR are not promotion-eligible).
            // A null value means the survival_state row is missing — treat
            // as "ineligible" on the FE side defensively.
            SurvivalStatus survivalStatus
    ) {
        public static MemberSummary from(RoomMember m, GroupMemberMinimum minimum) {
            return from(m, minimum, null);
        }

        public static MemberSummary from(
                RoomMember m, GroupMemberMinimum minimum, SurvivalStatus status) {
            int min = minimum != null ? minimum.getMinDailyGoalDays() : m.getRoom().getMinDailyGoalDays();
            int warnings = minimum != null ? minimum.getWarningCount() : 0;
            return new MemberSummary(
                    m.getRoom().getId(),
                    m.getUser().getId(),
                    m.getUser().getNickname(),
                    m.getRole(),
                    min,
                    warnings,
                    status
            );
        }
    }

    /**
     * Per-member daily snapshot returned by {@link #todayForRoom(User, long, LocalDate)}.
     * Field names mirror the FE {@code MemberTodayDto} contract in
     * {@code FE/src/api/rooms.ts}; Jackson serializes {@code date} as ISO
     * {@code yyyy-MM-dd} via the default date module.
     */
    public record MemberTodayDto(
            long userId,
            String nickname,
            LocalDate date,
            String goal,
            boolean goalSet,
            int completedTodoCount,
            boolean reflectionSubmitted,
            int currentStreak
    ) {}

    public record InviteSummary(long id, long roomId, String code, Instant expiresAt) {
        public static InviteSummary from(RoomInvite invite) {
            return new InviteSummary(
                    invite.getId(),
                    invite.getRoom().getId(),
                    invite.getCode(),
                    invite.getExpiresAt()
            );
        }
    }

    /**
     * Per-room run of the monthly minimum-days evaluator. Counts each
     * active member's mission-completed days in {@code month} (KST) and:
     * <ul>
     *   <li>writes an idempotent {@code group_warnings} audit row when the
     *       member fell short of their personal minimum,</li>
     *   <li>increments the membership-side {@code warning_count} (capped
     *       at 2 by {@link GroupMemberMinimum#incrementWarningCount()}),</li>
     *   <li>auto-removes the member at warning_count = 2 — except for the
     *       room owner, who keeps their seat so the room is never silently
     *       orphaned by the cron.</li>
     * </ul>
     *
     * <p>Idempotency lives at the audit row: a re-fired cron writes 0 rows
     * via the unique {@code (room_id, user_id, evaluation_month)} key, so
     * the membership counter never double-increments.
     *
     * <p>The auto-leave chat publish is registered for {@code afterCommit}
     * so a chat-row write that fails (room deleted mid-cron, …) cannot roll
     * back the warning audit. {@link ChatService#publishSystem} runs in
     * its own {@code REQUIRES_NEW} transaction for the same reason.
     */
    @Transactional
    public EvaluationResult evaluateRoom(long roomId, YearMonth month) {
        if (month == null) {
            throw new IllegalArgumentException("month is required");
        }
        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        Long ownerId = room.getOwner().getId();
        LocalDate evaluationMonth = month.atDay(1);
        String monthKey = month.toString();

        // Pessimistic-write so an overlapping evaluation for a *different*
        // YearMonth (admin backfill, retry) can't race-read warning_count
        // and clobber our increment with theirs. Same-month re-fires are
        // still squashed by the audit-row UNIQUE key inside the loop.
        List<GroupMemberMinimum> active = minimums.findByRoomIdForEvaluation(roomId);
        int evaluated = 0;
        int newWarnings = 0;
        int autoLefts = 0;

        for (GroupMemberMinimum membership : active) {
            evaluated += 1;
            int required = GoalMinimumDays.effectiveRequiredDays(
                    membership.getMinDailyGoalDays(), month);
            User member = users.findById(membership.getUserId()).orElse(null);
            if (member == null) {
                log.warn("[evaluator] orphaned minimum row roomId={} userId={}",
                        roomId, membership.getUserId());
                continue;
            }
            int completed;
            try {
                completed = dailyService.monthlyCompletedCount(member, monthKey);
            } catch (RuntimeException ex) {
                log.warn("[evaluator] monthly count failed roomId={} userId={}: {}",
                        roomId, member.getId(), ex.toString());
                continue;
            }
            if (completed >= required) {
                continue;
            }
            int prospective = Math.min(2, membership.getWarningCount() + 1);
            int inserted = warnings.insertIfAbsent(
                    roomId,
                    member.getId(),
                    evaluationMonth,
                    (short) Math.min(31, completed),
                    (short) Math.min(31, required),
                    (short) prospective);
            if (inserted == 0) {
                continue;
            }
            membership.incrementWarningCount();
            newWarnings += 1;
            if (membership.getWarningCount() >= 2 && !member.getId().equals(ownerId)) {
                roomMembers.deleteByRoomAndUser(room, member);
                publishAutoLeaveAfterCommit(roomId, member, month, completed, required);
                autoLefts += 1;
            } else if (membership.getWarningCount() >= 2) {
                log.info(
                        "[evaluator] owner reached 2 warnings room={} owner={} — keeping seat",
                        roomId, ownerId);
            }
        }
        return new EvaluationResult(roomId, month, evaluated, newWarnings, autoLefts);
    }

    private void publishAutoLeaveAfterCommit(
            long roomId, User member, YearMonth month, int completed, int required) {
        String body = member.getNickname() + "님이 최소 목표일수를 채우지 못해 그룹에서 자동 탈퇴되었어요.";
        String payload = String.format(
                "{\"userId\":%d,\"month\":\"%s\",\"completedDays\":%d,\"requiredDays\":%d}",
                member.getId(), month, completed, required);
        Runnable publish = () -> {
            try {
                chatService.publishSystem(roomId, ChatMessageKind.AUTO_LEAVE, body, payload);
            } catch (RuntimeException ex) {
                log.warn("[evaluator] AUTO_LEAVE chat publish failed roomId={} userId={}: {}",
                        roomId, member.getId(), ex.toString());
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

    public record EvaluationResult(
            long roomId,
            YearMonth month,
            int evaluatedMembers,
            int newWarnings,
            int autoLefts
    ) {}
}
