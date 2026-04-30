package com.yeosal.api.daily;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DailyController {
    private final DailyService dailyService;
    private final CurrentUser currentUser;

    public DailyController(DailyService dailyService, CurrentUser currentUser) {
        this.dailyService = dailyService;
        this.currentUser = currentUser;
    }

    @GetMapping("/daily-entries/today")
    public ApiResponse<DailyEntryDto> today(Authentication authentication) {
        return ApiResponse.of(dailyService.today(currentUser.require(authentication)));
    }

    @PostMapping("/daily-entries")
    public ApiResponse<DailyEntryDto> createDailyEntry(Authentication authentication, @Valid @RequestBody DailyEntryCreate request) {
        return ApiResponse.of(dailyService.createOrReplace(currentUser.require(authentication), request));
    }

    @PatchMapping("/daily-entries/today")
    public ApiResponse<DailyEntryDto> updateToday(Authentication authentication, @Valid @RequestBody DailyEntryUpdate request) {
        return ApiResponse.of(dailyService.updateToday(currentUser.require(authentication), request));
    }

    @PostMapping("/daily-entries/today/todo-items")
    public ApiResponse<TodoDto> createTodo(Authentication authentication, @Valid @RequestBody TodoCreate request) {
        return ApiResponse.of(dailyService.createTodo(currentUser.require(authentication), request));
    }

    @PatchMapping("/todo-items/{id}")
    public ApiResponse<TodoDto> updateTodo(Authentication authentication, @PathVariable long id, @Valid @RequestBody TodoUpdate request) {
        return ApiResponse.of(dailyService.updateTodo(currentUser.require(authentication), id, request));
    }

    @DeleteMapping("/todo-items/{id}")
    public ApiResponse<Void> deleteTodo(Authentication authentication, @PathVariable long id) {
        dailyService.deleteTodo(currentUser.require(authentication), id);
        return ApiResponse.of(null);
    }

    @PostMapping("/reflections")
    public ApiResponse<ReflectionDto> createReflection(Authentication authentication, @Valid @RequestBody ReflectionCreate request) {
        return ApiResponse.of(dailyService.createReflection(currentUser.require(authentication), request));
    }

    @GetMapping("/monthly-goals")
    public ApiResponse<MonthlyGoalDto> monthlyGoal(Authentication authentication, @RequestParam String month) {
        return ApiResponse.of(dailyService.monthlyGoal(currentUser.require(authentication), month));
    }

    @PostMapping("/monthly-goals")
    public ApiResponse<MonthlyGoalDto> createMonthlyGoal(Authentication authentication, @Valid @RequestBody MonthlyGoalCreate request) {
        return ApiResponse.of(dailyService.createMonthlyGoal(currentUser.require(authentication), request));
    }

    public record DailyEntryCreate(@NotBlank String goal, List<@NotBlank String> todos) {}
    public record DailyEntryUpdate(@NotBlank String goal) {}
    public record DailyEntryDto(long id, LocalDate date, String goal, List<TodoDto> todos, ReflectionDto reflection) {}
    public record TodoDto(long id, String title, boolean completed) {}
    public record TodoCreate(@NotBlank String title) {}
    public record TodoUpdate(String title, Boolean completed) {}
    public record ReflectionCreate(long dailyEntryId, @NotBlank String body) {}
    public record ReflectionDto(long id, long dailyEntryId, String body, boolean submitted) {}
    public record MonthlyGoalCreate(@NotBlank String month, @NotBlank String goal) {}
    public record MonthlyGoalDto(String month, String goal) {}
}
