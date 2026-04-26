package com.yeosal.api.stats;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.daily.DailyService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {
    private final DailyService dailyService;
    private final CurrentUser currentUser;

    public StatsController(DailyService dailyService, CurrentUser currentUser) {
        this.dailyService = dailyService;
        this.currentUser = currentUser;
    }

    @GetMapping("/monthly")
    public ApiResponse<MonthlyStatsDto> monthly(Authentication authentication, @RequestParam String month) {
        return ApiResponse.of(new MonthlyStatsDto(month, dailyService.monthlyCompletedCount(currentUser.require(authentication), month)));
    }

    public record MonthlyStatsDto(String month, int completedDailyCount) {}
}
