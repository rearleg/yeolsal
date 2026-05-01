package com.yeosal.api.daily;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyService {
    /** Event-hook debounce window. The notification log is consulted per-(user,kind),
     * so within a 30-minute span at most one FRIEND_GOAL or FRIEND_REFLECTION push
     * lands per recipient. */
    private static final Duration EVENT_DEBOUNCE = Duration.ofMinutes(30);

    private final DailyEntryRepository dailyEntries;
    private final TodoItemRepository todoItems;
    private final ReflectionRepository reflections;
    private final EntryDateResolver entryDateResolver;
    private final GateRule gateRule;
    private final Clock clock;
    private final RoomMemberRepository roomMembers;
    private final NotificationService notifications;
    private final DailyMissionCalculator calculator = new DailyMissionCalculator();

    public DailyService(
            DailyEntryRepository dailyEntries,
            TodoItemRepository todoItems,
            ReflectionRepository reflections,
            EntryDateResolver entryDateResolver,
            GateRule gateRule,
            Clock clock,
            RoomMemberRepository roomMembers,
            NotificationService notifications
    ) {
        this.dailyEntries = dailyEntries;
        this.todoItems = todoItems;
        this.reflections = reflections;
        this.entryDateResolver = entryDateResolver;
        this.gateRule = gateRule;
        this.clock = clock;
        this.roomMembers = roomMembers;
        this.notifications = notifications;
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
        boolean isNew = dailyEntries.findByUserAndDate(user, today).isEmpty();
        DailyEntry entry = dailyEntries.findByUserAndDate(user, today)
                .orElseGet(() -> new DailyEntry(user, today, request.goal()));
        entry.replace(request.goal(), request.todos() == null ? List.of() : request.todos());
        DailyController.DailyEntryDto dto = toDto(dailyEntries.save(entry));
        if (isNew) {
            fanOutEvent(user, NotificationKind.FRIEND_GOAL,
                    "FRIEND_GOAL:" + user.getId() + ":" + today,
                    user.getNickname() + "님이 오늘의 목표를 정했어요",
                    request.goal());
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
        DailyController.ReflectionDto dto = toDto(reflections.save(new Reflection(entry, request.body())));
        fanOutEvent(user, NotificationKind.FRIEND_REFLECTION,
                "FRIEND_REFLECTION:" + user.getId() + ":" + entry.getDate(),
                user.getNickname() + "님이 오늘 회고를 남겼어요",
                "프로필에서 함께 살펴봐요.");
        return dto;
    }

    /**
     * Pushes an event-style nudge to every fellow room-member of {@code actor}.
     * Each recipient's send is independently gated on prefs/quiet hours/debounce
     * inside {@link NotificationService#sendEvent}.
     */
    private void fanOutEvent(User actor, NotificationKind kind, String key, String title, String body) {
        for (User mate : roomMembers.findRoomMates(actor)) {
            notifications.sendEvent(mate, kind, key, title, body, EVENT_DEBOUNCE);
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
