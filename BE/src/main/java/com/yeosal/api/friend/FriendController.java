package com.yeosal.api.friend;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
public class FriendController {
    private final FriendService friendService;
    private final CurrentUser currentUser;

    public FriendController(FriendService friendService, CurrentUser currentUser) {
        this.friendService = friendService;
        this.currentUser = currentUser;
    }

    @GetMapping("/friends/requests")
    public ApiResponse<List<FriendRequestDto>> requests(Authentication authentication) {
        return ApiResponse.of(friendService.requests(currentUser.require(authentication)));
    }

    @PostMapping("/friends/requests")
    public ApiResponse<FriendRequestDto> request(Authentication authentication, @Valid @RequestBody FriendRequestCreate request) {
        return ApiResponse.of(friendService.request(currentUser.require(authentication), request));
    }

    @PatchMapping("/friends/requests/{id}")
    public ApiResponse<FriendRequestDto> respond(Authentication authentication, @PathVariable long id, @Valid @RequestBody FriendRequestDecision request) {
        return ApiResponse.of(friendService.respond(currentUser.require(authentication), id, request));
    }

    @GetMapping("/feed/daily")
    public ApiResponse<List<DailyFeedItem>> dailyFeed(
            Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ApiResponse.of(friendService.dailyFeed(currentUser.require(authentication), date));
    }

    public record FriendRequestCreate(@NotBlank String targetEmail) {}
    public record FriendRequestDecision(boolean accepted) {}
    public record FriendRequestDto(long id, String requesterEmail, String requesterNickname, String status) {}
    public record DailyFeedItem(long userId, String nickname, LocalDate date, String goal, int completedTodoCount, boolean reflectionSubmitted, int currentStreak) {}
}
