package com.yeosal.api.stats;

import com.yeosal.api.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {
    @GetMapping("/monthly")
    public ApiResponse<MonthlyStatsDto> monthly(@RequestParam String month) {
        return ApiResponse.of(new MonthlyStatsDto(month, 18));
    }

    public record MonthlyStatsDto(String month, int completedDailyCount) {}
}
