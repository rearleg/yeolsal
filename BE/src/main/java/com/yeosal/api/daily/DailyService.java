package com.yeosal.api.daily;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.user.User;
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
    private final DailyMissionCalculator calculator = new DailyMissionCalculator();

    public DailyService(
            DailyEntryRepository dailyEntries,
            TodoItemRepository todoItems,
            ReflectionRepository reflections,
            MonthlyGoalRepository monthlyGoals
    ) {
        this.dailyEntries = dailyEntries;
        this.todoItems = todoItems;
        this.reflections = reflections;
        this.monthlyGoals = monthlyGoals;
    }

    @Transactional(readOnly = true)
    public DailyController.DailyEntryDto today(User user) {
        LocalDate today = LocalDate.now(ZoneId.of(user.getTimezone()));
        return dailyEntries.findByUserAndDate(user, today).map(this::toDto).orElse(null);
    }

    @Transactional
    public DailyController.DailyEntryDto createOrReplace(User user, DailyController.DailyEntryCreate request) {
        LocalDate today = LocalDate.now(ZoneId.of(user.getTimezone()));
        DailyEntry entry = dailyEntries.findByUserAndDate(user, today)
                .orElseGet(() -> new DailyEntry(user, today, request.goal()));
        entry.replace(request.goal(), request.todos());
        return toDto(dailyEntries.save(entry));
    }

    @Transactional
    public DailyController.TodoDto updateTodo(User user, long id, DailyController.TodoUpdate request) {
        TodoItem todo = todoItems.findById(id).orElseThrow(() -> new NotFoundException("Todo를 찾을 수 없습니다."));
        if (!todo.getDailyEntry().getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Todo 수정 권한이 없습니다.");
        }
        todo.setCompleted(request.completed());
        return toDto(todo);
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
                    boolean reflectionSubmitted = entry.getReflection() != null;
                    boolean missionCompleted = reflectionSubmitted && calculator.calculate(
                            entry.getDate(),
                            true,
                            entry.getReflection().getSubmittedAt().atZone(ZoneId.of(user.getTimezone()))
                    ).missionCompleted();
                    return new GrassDay(date, missionCompleted, completedTodos, reflectionSubmitted, Math.min(4, completedTodos));
                })
                .orElse(new GrassDay(date, false, 0, false, 0))).toList();
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
