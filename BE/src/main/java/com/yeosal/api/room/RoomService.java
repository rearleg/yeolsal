package com.yeosal.api.room;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.daily.TodoItem;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
            com.yeosal.api.realtime.RealtimePublisher realtime
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
    }

    /** Backwards-compatible overload that creates a room with the default minimum. */
    public RoomSummary create(User owner, String name) {
        return create(owner, name, GoalMinimumDays.DEFAULT);
    }

    @Transactional
    public RoomSummary create(User owner, String name, int minDailyGoalDays) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("방 이름은 비어있을 수 없습니다.");
        }
        // Whitelist check runs on the full int so out-of-range values
        // (including ones that would alias to an allowed short) are rejected.
        if (!GoalMinimumDays.isAllowed(minDailyGoalDays)) {
            throw new BadRequestException("최소 목표일수는 10/15/20/매일 중 하나여야 합니다.");
        }
        short min = (short) minDailyGoalDays;
        Room room = rooms.save(new Room(name.trim(), owner, min));
        roomMembers.save(new RoomMember(room, owner, RoomRole.OWNER));
        // Owner's per-member row mirrors the room minimum at creation time.
        minimums.save(new GroupMemberMinimum(room.getId(), owner.getId(), min));
        return RoomSummary.from(room);
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> myRooms(User user) {
        return roomMembers.findByUser(user).stream()
                .map(RoomMember::getRoom)
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
        return roomMembers.findByRoom(room).stream()
                .map(rm -> MemberSummary.from(rm, byUserId.get(rm.getUser().getId())))
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

        return members.stream()
                .map(rm -> {
                    User u = rm.getUser();
                    DailyEntry entry = todayByUserId.get(u.getId());
                    List<DailyEntry> streakRows = streakByUserId.getOrDefault(u.getId(), List.of());
                    List<GrassDay> window = dailyService.grassFromEntries(
                            u, streakFrom, date, streakRows);
                    int streak = dailyService.streakFromGrass(date, window);
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
        Room room = invite.getRoom();

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
        RoomMember saved = roomMembers.save(new RoomMember(room, user, RoomRole.MEMBER));
        // New members start by mirroring the room's current minimum. They can
        // raise (but not lower) their own minimum later via the PATCH endpoint
        // introduced in PR E.
        GroupMemberMinimum minimum = minimums.save(new GroupMemberMinimum(
                room.getId(), user.getId(), room.getMinDailyGoalDays()));
        MemberSummary summary = MemberSummary.from(saved, minimum);
        // Realtime fan-out — every existing member subscribed to
        // /topic/rooms.{id}.members sees the new member without waiting
        // for a manual refresh. Publisher swallows broker errors so a
        // WS hiccup never rolls back the join itself.
        realtime.publishMemberAdded(room.getId(), summary);
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

    private Room requireRoom(long roomId) {
        return rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
    }

    private RoomMember requireMembership(Room room, User user) {
        return roomMembers.findByRoomAndUser(room, user)
                .orElseThrow(() -> new ForbiddenException("방 멤버만 접근할 수 있습니다."));
    }

    public record RoomSummary(long id, String name, long ownerId, int maxMembers, int minDailyGoalDays) {
        public static RoomSummary from(Room room) {
            return new RoomSummary(
                    room.getId(),
                    room.getName(),
                    room.getOwner().getId(),
                    room.getMaxMembers(),
                    room.getMinDailyGoalDays()
            );
        }
    }

    public record MemberSummary(
            long roomId,
            long userId,
            String nickname,
            RoomRole role,
            int currentMinimum,
            int warningCount
    ) {
        public static MemberSummary from(RoomMember m, GroupMemberMinimum minimum) {
            int min = minimum != null ? minimum.getMinDailyGoalDays() : m.getRoom().getMinDailyGoalDays();
            int warnings = minimum != null ? minimum.getWarningCount() : 0;
            return new MemberSummary(
                    m.getRoom().getId(),
                    m.getUser().getId(),
                    m.getUser().getNickname(),
                    m.getRole(),
                    min,
                    warnings
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
