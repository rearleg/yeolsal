package com.yeosal.api.daily;

import com.yeosal.api.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DailyController {
    @PostMapping("/daily-entries")
    public ApiResponse<DailyEntryDto> createDailyEntry(@Valid @RequestBody DailyEntryCreate request) {
        return ApiResponse.of(new DailyEntryDto(1L, LocalDate.now(), request.goal(), request.todos()));
    }

    @PostMapping("/reflections")
    public ApiResponse<ReflectionDto> createReflection(@Valid @RequestBody ReflectionCreate request) {
        return ApiResponse.of(new ReflectionDto(1L, request.dailyEntryId(), request.body(), true));
    }

    @GetMapping("/monthly-goals")
    public ApiResponse<MonthlyGoalDto> monthlyGoal(@RequestParam String month) {
        return ApiResponse.of(new MonthlyGoalDto(month, "이번 달 20일 성공"));
    }

    @PostMapping("/monthly-goals")
    public ApiResponse<MonthlyGoalDto> createMonthlyGoal(@Valid @RequestBody MonthlyGoalCreate request) {
        return ApiResponse.of(new MonthlyGoalDto(request.month(), request.goal()));
    }

    public record DailyEntryCreate(@NotBlank String goal, @NotEmpty List<@NotBlank String> todos) {}
    public record DailyEntryDto(long id, LocalDate date, String goal, List<String> todos) {}
    public record ReflectionCreate(long dailyEntryId, @NotBlank String body) {}
    public record ReflectionDto(long id, long dailyEntryId, String body, boolean submitted) {}
    public record MonthlyGoalCreate(@NotBlank String month, @NotBlank String goal) {}
    public record MonthlyGoalDto(String month, String goal) {}
}
