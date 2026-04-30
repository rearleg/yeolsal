package com.yeosal.api.daily;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyService {
    private final DailyEntryRepository dailyEntries;
    private final TodoItemRepository todoItems;
    private final ReflectionRepository reflections;
    private final MonthlyGoalRepository monthlyGoals;
    private final EntryDateResolver entryDateResolver;
    private final GateRule gateRule;
    private final Clock clock;
    private final DailyMissionCalculator calculator = new DailyMissionCalculator();

    public DailyService(
            DailyEntryRepository dailyEntries,
            TodoItemRepository todoItems,
            ReflectionRepository reflections,
            MonthlyGoalRepository monthlyGoals,
            EntryDateResolver entryDateResolver,
            GateRule gateRule,
            Clock clock
    ) {
        this.dailyEntries = dailyEntries;
        this.todoItems = todoItems;
        this.reflections = reflections;
        this.monthlyGoals = monthlyGoals;
        this.entryDateResolver = entryDateResolver;
        this.gateRule = gateRule;
        this.clock = clock;
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
        DailyEntry entry = dailyEntries.findByUserAndDate(user, today)
                .orElseGet(() -> new DailyEntry(user, today, request.goal()));
        entry.replace(request.goal(), request.todos() == null ? List.of() : request.todos());
        return toDto(dailyEntries.save(entry));
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
        return toDto(reflections.save(new Reflection(entry, request.body())));
    }

    @Transactional(readOnly = true)
    public DailyController.MonthlyGoalDto monthlyGoal(User user, String month) {
        return monthlyGoals.findByUserAndMonth(user, month)
                .map(goal -> new DailyController.MonthlyGoalDto(goal.getMonth(), goal.getGoal()))
                .orElse(new DailyController.MonthlyGoalDto(month, ""));
    }

    @Transactional
    public DailyController.MonthlyGoalDto createMonthlyGoal(User user, DailyController.MonthlyGoalCreate request) {
        MonthlyGoal goal = monthlyGoals.findByUserAndMonth(user, request.month())
                .orElseGet(() -> new MonthlyGoal(user, request.month(), request.goal()));
        goal.setGoal(request.goal());
        MonthlyGoal saved = monthlyGoals.save(goal);
        return new DailyController.MonthlyGoalDto(saved.getMonth(), saved.getGoal());
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
        List<DailyEntry> entries = dailyEntries.findByUserAndDateBetween(user, from, to);
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
        List<GrassDay> window = grass(user, from, today);
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

    public DailyController.DailyEntryDto toDto(DailyEntry entry) {
        return new DailyController.DailyEntryDto(
                entry.getId(),
                entry.getDate(),
                entry.getGoal(),
                entry.getTodos().stream().map(this::toDto).toList(),
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
