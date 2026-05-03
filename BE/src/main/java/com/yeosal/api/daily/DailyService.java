package com.yeosal.api.daily;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DailyService {
    /** Event-hook debounce window. The notification log is consulted per-(user,kind),
     * so within a 30-minute span at most one FRIEND_GOAL or FRIEND_REFLECTION push
     * lands per recipient. */
    private static final Duration EVENT_DEBOUNCE = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(DailyService.class);

    private final DailyEntryRepository dailyEntries;
    private final TodoItemRepository todoItems;
    private final ReflectionRepository reflections;
    private final EntryDateResolver entryDateResolver;
    private final GateRule gateRule;
    private final Clock clock;
    private final RoomMemberRepository roomMembers;
    private final NotificationService notifications;
    private final ChatService chatService;
    private final DailyMissionCalculator calculator = new DailyMissionCalculator();

    public DailyService(
            DailyEntryRepository dailyEntries,
            TodoItemRepository todoItems,
            ReflectionRepository reflections,
            EntryDateResolver entryDateResolver,
            GateRule gateRule,
            Clock clock,
            RoomMemberRepository roomMembers,
            NotificationService notifications,
            ChatService chatService
    ) {
        this.dailyEntries = dailyEntries;
        this.todoItems = todoItems;
        this.reflections = reflections;
        this.entryDateResolver = entryDateResolver;
        this.gateRule = gateRule;
        this.clock = clock;
        this.roomMembers = roomMembers;
        this.notifications = notifications;
        this.chatService = chatService;
    }

    private LocalDate currentEntryDate(User user) {
        return entryDateResolver.resolve(clock.instant(), ZoneId.of(user.getTimezone()));
    }

    @Transactional(readOnly = true)
    public DailyController.DailyEntryDto today(User user) {
        LocalDate today = currentEntryDate(user);
        return dailyEntries.findByUserAndDate(user, today).map(this::toDto).orElse(null);
    }

    @Transactional
    public DailyController.DailyEntryDto createOrReplace(User user, DailyController.DailyEntryCreate request) {
        LocalDate today = currentEntryDate(user);
        java.util.Optional<DailyEntry> existing = dailyEntries.findByUserAndDate(user, today);
        // The hook fires once per (user, day) on the *first* non-blank goal,
        // not once per entry-row creation. An empty entry left behind by an
        // earlier today() poll or a failed save would otherwise swallow the
        // GOAL chat row even on the user's very first goal text.
        boolean firstGoalToday = existing
                .map(e -> e.getGoal() == null || e.getGoal().isBlank())
                .orElse(true);
        DailyEntry entry = existing
                .orElseGet(() -> new DailyEntry(user, today, request.goal()));
        entry.replace(request.goal(), request.todos() == null ? List.of() : request.todos());
        DailyController.DailyEntryDto dto = toDto(dailyEntries.save(entry));
        boolean newGoalIsNonBlank = request.goal() != null && !request.goal().isBlank();
        if (firstGoalToday && newGoalIsNonBlank) {
            fanOutEvent(user, NotificationKind.FRIEND_GOAL,
                    "FRIEND_GOAL:" + user.getId() + ":" + today,
                    user.getNickname() + "님이 오늘의 목표를 정했어요",
                    request.goal());
            publishGoalSystemMessages(user, today, entry.getId());
        }
        return dto;
    }

    @Transactional
    public DailyController.DailyEntryDto updateToday(User user, DailyController.DailyEntryUpdate request) {
        LocalDate today = currentEntryDate(user);
        DailyEntry entry = dailyEntries.findByUserAndDate(user, today)
                .orElseGet(() -> new DailyEntry(user, today, request.goal().trim()));
        entry.setGoal(request.goal().trim());
        return toDto(dailyEntries.save(entry));
    }

    @Transactional
    public DailyController.TodoDto createTodo(User user, DailyController.TodoCreate request) {
        LocalDate today = currentEntryDate(user);
        DailyEntry entry = dailyEntries.findByUserAndDate(user, today)
                .orElseThrow(() -> new BadRequestException("오늘의 목표를 먼저 저장하세요."));
        TodoItem todo = new TodoItem(entry, request.title().trim());
        return toDto(todoItems.save(todo));
    }

    @Transactional
    public DailyController.TodoDto updateTodo(User user, long id, DailyController.TodoUpdate request) {
        TodoItem todo = todoItems.findById(id).orElseThrow(() -> new NotFoundException("Todo를 찾을 수 없습니다."));
        if (!todo.getDailyEntry().getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Todo 수정 권한이 없습니다.");
        }
        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isBlank()) {
                throw new BadRequestException("Todo 제목을 입력하세요.");
            }
            todo.setTitle(title);
        }
        if (request.completed() != null) {
            todo.setCompleted(request.completed());
        }
        return toDto(todo);
    }

    @Transactional
    public void deleteTodo(User user, long id) {
        TodoItem todo = todoItems.findById(id).orElseThrow(() -> new NotFoundException("Todo를 찾을 수 없습니다."));
        if (!todo.getDailyEntry().getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Todo 삭제 권한이 없습니다.");
        }
        todoItems.delete(todo);
    }

    @Transactional
    public DailyController.ReflectionDto createReflection(User user, DailyController.ReflectionCreate request) {
        DailyEntry entry = dailyEntries.findById(request.dailyEntryId())
                .orElseThrow(() -> new NotFoundException("Daily entry를 찾을 수 없습니다."));
        if (!entry.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("회고 작성 권한이 없습니다.");
        }
        if (reflections.findByDailyEntry(entry).isPresent()) {
            throw new BadRequestException("이미 회고를 제출했습니다.");
        }
        Reflection saved;
        try {
            // Flush eagerly so a concurrent duplicate insert (FE retry / double-tap)
            // surfaces the unique-constraint failure here and we can remap to a 400
            // envelope instead of leaking a 500 at commit time.
            saved = reflections.saveAndFlush(new Reflection(entry, request.body()));
        } catch (DataIntegrityViolationException dup) {
            throw new BadRequestException("이미 회고를 제출했습니다.");
        }
        DailyController.ReflectionDto dto = toDto(saved);
        fanOutEvent(user, NotificationKind.FRIEND_REFLECTION,
                "FRIEND_REFLECTION:" + user.getId() + ":" + entry.getDate(),
                user.getNickname() + "님이 오늘 회고를 남겼어요",
                "프로필에서 함께 살펴봐요.");
        publishReflectionSystemMessages(user, entry.getDate(), entry.getId());
        publishMilestoneSystemMessages(user, entry.getDate());
        return dto;
    }

    /**
     * Fans a {@code GOAL} system row to every room {@code user} belongs to.
     * Scheduled to run after the actor's transaction commits — that way a
     * chat row never appears for a daily-entry write that ultimately rolled
     * back, and a chat fan-out failure can never roll back the actor in the
     * first place. Per-room {@code publishSystem} failures (and even the
     * room lookup itself) are caught and logged inline.
     */
    private void publishGoalSystemMessages(User user, LocalDate date, long dailyEntryId) {
        String body = user.getNickname() + "님이 오늘의 목표를 작성했어요.";
        String payload = systemPayload(user.getId(), dailyEntryId, date);
        publishAfterCommit(() -> publishSystemMessages(user, ChatMessageKind.GOAL, body, payload));
    }

    private void publishReflectionSystemMessages(User user, LocalDate date, long dailyEntryId) {
        String body = user.getNickname() + "님이 오늘 회고를 남겼어요.";
        String payload = systemPayload(user.getId(), dailyEntryId, date);
        publishAfterCommit(() -> publishSystemMessages(user, ChatMessageKind.REFLECTION, body, payload));
    }

    /**
     * Plan 7.1 MILESTONE hook. After today's reflection is saved, count
     * the actor's mission-completed days for the current calendar month
     * and let {@link ChatService#publishMilestonesForActor} fan a single
     * MILESTONE chat row per (room, actor, month) when the actor's
     * personal minimum has been hit. Idempotency lives at ChatService
     * via {@code existsMilestoneForMonth}, so a re-fire after a retry
     * or another reflection in the same month is a no-op.
     *
     * <p>Gated on {@code afterCommit} so a chat-row failure cannot roll
     * back the reflection write, and a rolled-back reflection cannot
     * leave a stale milestone broadcast behind. Errors inside the
     * publish path are caught here so they cannot leak into the actor
     * trace.
     */
    private void publishMilestoneSystemMessages(User user, LocalDate date) {
        YearMonth month = YearMonth.from(date);
        int completed;
        try {
            completed = monthlyCompletedCount(user, month.toString());
        } catch (RuntimeException ex) {
            log.warn("[chat] milestone count failed actor={} month={}: {}",
                    user.getId(), month, ex.toString());
            return;
        }
        publishAfterCommit(() -> {
            try {
                chatService.publishMilestonesForActor(user, month, date, completed);
            } catch (RuntimeException ex) {
                log.warn("[chat] milestone fan-out failed actor={} date={}: {}",
                        user.getId(), date, ex.toString());
            }
        });
    }

    /**
     * Defers {@code task} until the surrounding @Transactional commits, or
     * runs it inline when invoked outside a transaction (test fixtures).
     */
    private void publishAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private void publishSystemMessages(User user, ChatMessageKind kind, String body, String payload) {
        List<Room> rooms;
        try {
            rooms = roomMembers.findRoomsByUser(user);
        } catch (RuntimeException ex) {
            log.warn("[chat] system publish room lookup failed actor={} kind={}: {}",
                    user.getId(), kind, ex.toString());
            return;
        }
        for (Room room : rooms) {
            try {
                chatService.publishSystem(room.getId(), kind, body, payload);
            } catch (RuntimeException ex) {
                log.warn("[chat] system publish failed actor={} room={} kind={}: {}",
                        user.getId(), room.getId(), kind, ex.toString());
            }
        }
    }

    private static String systemPayload(long actorUserId, long dailyEntryId, LocalDate date) {
        // The fields are all numbers / ISO date — no escaping required, so a
        // direct String.format is cheaper than spinning up Jackson per call.
        return String.format(
                "{\"actorUserId\":%d,\"dailyEntryId\":%d,\"date\":\"%s\"}",
                actorUserId, dailyEntryId, date);
    }

    /**
     * Pushes an event-style nudge to every fellow room-member of {@code actor}.
     * Each recipient's send is independently gated on prefs/quiet hours/debounce
     * inside {@link NotificationService#sendEvent}. Per-mate failures (bad
     * timezone, push provider hiccup, …) are isolated so the actor's primary
     * write transaction never rolls back because of a notification side effect.
     */
    private void fanOutEvent(User actor, NotificationKind kind, String key, String title, String body) {
        for (User mate : roomMembers.findRoomMates(actor)) {
            try {
                notifications.sendEvent(mate, kind, key, title, body, EVENT_DEBOUNCE);
            } catch (RuntimeException ex) {
                log.warn("[notif] fan-out failed actor={} mate={} kind={}: {}",
                        actor.getId(), mate.getId(), kind, ex.toString());
            }
        }
    }

    @Transactional(readOnly = true)
    public int monthlyCompletedCount(User user, String month) {
        YearMonth yearMonth = YearMonth.parse(month);
        return (int) grass(user, yearMonth.atDay(1), yearMonth.atEndOfMonth()).stream()
                .filter(GrassDay::missionCompleted)
                .count();
    }

    @Transactional(readOnly = true)
    public List<GrassDay> grass(User user, LocalDate from, LocalDate to) {
        return grassFromEntries(user, from, to, dailyEntries.findByUserAndDateBetween(user, from, to));
    }

    /**
     * Compute the grass window from a pre-fetched entry list. Used by the
     * friend feed to share a single batched query across all friends so
     * streak computation does not trigger per-entry lazy-load N+1.
     */
    public List<GrassDay> grassFromEntries(User user, LocalDate from, LocalDate to, List<DailyEntry> entries) {
        return from.datesUntil(to.plusDays(1)).map(date -> entries.stream()
                .filter(entry -> entry.getDate().equals(date))
                .findFirst()
                .map(entry -> {
                    int completedTodos = (int) entry.getTodos().stream().filter(TodoItem::isCompleted).count();
                    boolean goalSet = entry.getGoal() != null && !entry.getGoal().isBlank();
                    boolean reflectionSubmitted = entry.getReflection() != null;
                    boolean missionCompleted = reflectionSubmitted && calculator.calculate(
                            entry.getDate(),
                            true,
                            entry.getReflection().getSubmittedAt().atZone(ZoneId.of(user.getTimezone()))
                    ).missionCompleted();
                    int intensity = gateRule.bucket(goalSet, reflectionSubmitted, completedTodos);
                    return new GrassDay(date, missionCompleted, completedTodos, reflectionSubmitted, intensity);
                })
                .orElse(new GrassDay(date, false, 0, false, 0))).toList();
    }

    /**
     * Current consecutive run of "gate-passing" days ending today (or yesterday
     * if today's entry isn't recorded yet — today doesn't break the streak).
     * Caps at {@code windowDays} for cost. Pass 365 to cover a year.
     */
    @Transactional(readOnly = true)
    public int currentStreak(User user, int windowDays) {
        LocalDate today = currentEntryDate(user);
        LocalDate from = today.minusDays(Math.max(1, windowDays) - 1);
        return streakFromGrass(today, grass(user, from, today));
    }

    /**
     * Walk a grass window backwards and count gate-passing days. Pure
     * function over a pre-computed grass list so callers can share one
     * batched query across multiple users (see FriendService.dailyFeed).
     */
    public int streakFromGrass(LocalDate today, List<GrassDay> window) {
        int streak = 0;
        for (int i = window.size() - 1; i >= 0; i -= 1) {
            GrassDay d = window.get(i);
            if (d.intensity() > 0) {
                streak += 1;
            } else if (i == window.size() - 1 && d.date().equals(today)) {
                // today not recorded yet — keep counting backwards without breaking
                continue;
            } else {
                break;
            }
        }
        return streak;
    }

    /**
     * Most recent reflections for a user, newest first. Caller is responsible
     * for visibility checks; this method does not consult any room/friend gate.
     * Returns a flat view record so lazy fields are resolved inside the
     * transaction boundary.
     */
    @Transactional(readOnly = true)
    public List<ReflectionView> recentReflections(User user, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        return reflections.findRecentByUser(user, PageRequest.of(0, capped)).stream()
                .map(r -> new ReflectionView(
                        r.getDailyEntry().getDate(),
                        r.getBody(),
                        r.getSubmittedAt()))
                .toList();
    }

    public record ReflectionView(java.time.LocalDate date, String body, java.time.Instant submittedAt) {}

    public DailyController.DailyEntryDto toDto(DailyEntry entry) {
        return new DailyController.DailyEntryDto(
                entry.getId(),
                entry.getDate(),
                entry.getGoal(),
                entry.getTodos().stream()
                        .sorted(Comparator.comparing(TodoItem::getId))
                        .map(this::toDto)
                        .toList(),
                entry.getReflection() == null ? null : toDto(entry.getReflection())
        );
    }

    private DailyController.TodoDto toDto(TodoItem todo) {
        return new DailyController.TodoDto(todo.getId(), todo.getTitle(), todo.isCompleted());
    }

    private DailyController.ReflectionDto toDto(Reflection reflection) {
        return new DailyController.ReflectionDto(reflection.getId(), reflection.getDailyEntry().getId(), reflection.getBody(), true);
    }
}
