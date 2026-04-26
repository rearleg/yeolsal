package com.yeosal.api.friend;

import com.yeosal.api.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
public class FriendController {
    @GetMapping("/friends")
    public ApiResponse<List<FriendDto>> friends() {
        return ApiResponse.of(List.of(new FriendDto(2L, "민서", "ACCEPTED")));
    }

    @PostMapping("/friends/requests")
    public ApiResponse<FriendRequestDto> request(@Valid @RequestBody FriendRequestCreate request) {
        return ApiResponse.of(new FriendRequestDto(1L, request.targetEmail(), "PENDING"));
    }

    @PatchMapping("/friends/requests/{id}")
    public ApiResponse<FriendRequestDto> respond(@PathVariable long id, @Valid @RequestBody FriendRequestDecision request) {
        return ApiResponse.of(new FriendRequestDto(id, "friend@example.com", request.accepted() ? "ACCEPTED" : "DECLINED"));
    }

    @GetMapping("/feed/daily")
    public ApiResponse<List<DailyFeedItem>> dailyFeed(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ApiResponse.of(List.of(new DailyFeedItem(2L, "민서", date, "영어 단어 30개", 4, true)));
    }

    public record FriendDto(long userId, String nickname, String status) {}
    public record FriendRequestCreate(@NotBlank String targetEmail) {}
    public record FriendRequestDecision(boolean accepted) {}
    public record FriendRequestDto(long id, String targetEmail, String status) {}
    public record DailyFeedItem(long userId, String nickname, LocalDate date, String goal, int completedTodoCount, boolean reflectionSubmitted) {}
}
