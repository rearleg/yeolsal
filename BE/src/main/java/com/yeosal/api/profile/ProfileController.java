package com.yeosal.api.profile;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.friend.FriendService;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final FriendService friendService;
    private final DailyService dailyService;

    public ProfileController(CurrentUser currentUser, UserRepository users, FriendService friendService, DailyService dailyService) {
        this.currentUser = currentUser;
        this.users = users;
        this.friendService = friendService;
        this.dailyService = dailyService;
    }

    @GetMapping("/me")
    public ApiResponse<ProfileDto> me(Authentication authentication) {
        return ApiResponse.of(ProfileDto.from(currentUser.require(authentication)));
    }

    @GetMapping("/{userId}")
    public ApiResponse<ProfileDto> profile(Authentication authentication, @PathVariable long userId) {
        User viewer = currentUser.require(authentication);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (!friendService.canView(viewer, target)) {
            throw new ForbiddenException("프로필 접근 권한이 없습니다.");
        }
        return ApiResponse.of(ProfileDto.from(target));
    }

    @GetMapping("/{userId}/grass")
    public ApiResponse<List<GrassDayDto>> grass(
            Authentication authentication,
            @PathVariable long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        User viewer = currentUser.require(authentication);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (!friendService.canView(viewer, target)) {
            throw new ForbiddenException("잔디 접근 권한이 없습니다.");
        }
        return ApiResponse.of(dailyService.grass(target, from, to).stream()
                .map(day -> new GrassDayDto(day.date(), day.missionCompleted(), day.completedTodoCount(), day.reflectionSubmitted(), day.intensity()))
                .toList());
    }

    @GetMapping("/me/grass")
    public ApiResponse<List<GrassDayDto>> myGrass(
            Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        User user = currentUser.require(authentication);
        return ApiResponse.of(dailyService.grass(user, from, to).stream()
                .map(day -> new GrassDayDto(day.date(), day.missionCompleted(), day.completedTodoCount(), day.reflectionSubmitted(), day.intensity()))
                .toList());
    }

    public record ProfileDto(long userId, String email, String nickname, String timezone) {
        static ProfileDto from(User user) {
            return new ProfileDto(user.getId(), user.getEmail(), user.getNickname(), user.getTimezone());
        }
    }
    public record GrassDayDto(LocalDate date, boolean missionCompleted, int completedTodoCount, boolean reflectionSubmitted, int intensity) {}
}
