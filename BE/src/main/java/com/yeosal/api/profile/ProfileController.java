package com.yeosal.api.profile;

import com.yeosal.api.common.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {
    @GetMapping("/me")
    public ApiResponse<ProfileDto> me() {
        return ApiResponse.of(new ProfileDto(1L, "나", "Asia/Seoul"));
    }

    @GetMapping("/{userId}")
    public ApiResponse<ProfileDto> profile(@PathVariable long userId) {
        return ApiResponse.of(new ProfileDto(userId, "민서", "Asia/Seoul"));
    }

    @GetMapping("/{userId}/grass")
    public ApiResponse<List<GrassDayDto>> grass(
            @PathVariable long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        long days = Math.max(0, from.datesUntil(to.plusDays(1)).count());
        return ApiResponse.of(IntStream.range(0, (int) days)
                .mapToObj(index -> {
                    int completedTodos = index % 5 == 0 ? 0 : (index % 4) + 1;
                    return new GrassDayDto(from.plusDays(index), completedTodos > 0, completedTodos, completedTodos > 0, Math.min(4, completedTodos));
                })
                .toList());
    }

    public record ProfileDto(long userId, String nickname, String timezone) {}
    public record GrassDayDto(LocalDate date, boolean missionCompleted, int completedTodoCount, boolean reflectionSubmitted, int intensity) {}
}
