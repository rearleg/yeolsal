package com.yeosal.api.profile;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.friend.FriendService;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private final RoomMemberRepository roomMembers;

    public ProfileController(
            CurrentUser currentUser,
            UserRepository users,
            FriendService friendService,
            DailyService dailyService,
            RoomMemberRepository roomMembers
    ) {
        this.currentUser = currentUser;
        this.users = users;
        this.friendService = friendService;
        this.dailyService = dailyService;
        this.roomMembers = roomMembers;
    }

    @GetMapping("/me")
    public ApiResponse<ProfileDto> me(Authentication authentication) {
        return ApiResponse.of(ProfileDto.from(currentUser.require(authentication)));
    }

    @GetMapping("/{userId}")
    public ApiResponse<PublicProfileDto> profile(Authentication authentication, @PathVariable long userId) {
        User viewer = currentUser.require(authentication);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (!friendService.canView(viewer, target)) {
            throw new ForbiddenException("프로필 접근 권한이 없습니다.");
        }
        return ApiResponse.of(PublicProfileDto.from(target));
    }

    @GetMapping("/{userId}/grass")
    public ApiResponse<List<GrassDayDto>> grass(
            Authentication authentication,
            @PathVariable long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        validateGrassRange(from, to);
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
        validateGrassRange(from, to);
        User user = currentUser.require(authentication);
        return ApiResponse.of(dailyService.grass(user, from, to).stream()
                .map(day -> new GrassDayDto(day.date(), day.missionCompleted(), day.completedTodoCount(), day.reflectionSubmitted(), day.intensity()))
                .toList());
    }

    private static final long MAX_GRASS_RANGE_DAYS = 366;

    /**
     * Bounds-check the grass / contributions date range so callers cannot
     * push a 100-year window through and force the BE to materialize an
     * unbounded grass list. Inclusive on both ends.
     */
    private static void validateGrassRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are required.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("from must be on or before to.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_GRASS_RANGE_DAYS) {
            throw new BadRequestException("date range exceeds " + MAX_GRASS_RANGE_DAYS + " days.");
        }
    }

    /**
     * Rolling-window contributions for the GitHub-style 잔디 grid.
     * Returns the most recent {@code days} entries ending today (06:00 boundary).
     * Visibility: viewer must be self or share a confirmed friendship (Phase 4-A
     * will extend this to "shares at least one room").
     */
    @GetMapping("/{userId}/contributions")
    public ApiResponse<List<GrassDayDto>> contributions(
            Authentication authentication,
            @PathVariable long userId,
            @RequestParam(defaultValue = "365") int days
    ) {
        User viewer = currentUser.require(authentication);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (!friendService.canView(viewer, target)) {
            throw new ForbiddenException("잔디 접근 권한이 없습니다.");
        }
        int windowDays = Math.max(1, Math.min(days, 366));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(windowDays - 1L);
        return ApiResponse.of(dailyService.grass(target, from, to).stream()
                .map(day -> new GrassDayDto(day.date(), day.missionCompleted(), day.completedTodoCount(), day.reflectionSubmitted(), day.intensity()))
                .toList());
    }

    @GetMapping("/{userId}/streak")
    public ApiResponse<StreakDto> streak(
            Authentication authentication,
            @PathVariable long userId,
            @RequestParam(defaultValue = "365") int windowDays
    ) {
        User viewer = currentUser.require(authentication);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (!friendService.canView(viewer, target)) {
            throw new ForbiddenException("스트릭 접근 권한이 없습니다.");
        }
        int capped = Math.max(1, Math.min(windowDays, 366));
        return ApiResponse.of(new StreakDto(target.getId(), dailyService.currentStreak(target, capped)));
    }

    /**
     * Recent reflections for the target user. Visibility:
     * <ul>
     *   <li>403 if viewer cannot see the target at all (no friendship, no shared room).</li>
     *   <li>{@code body} present only when viewer and target share at least one
     *       room. For friend-only viewers, the body is masked (null) but
     *       metadata (date, submittedAt) is returned so the FE can render
     *       "회고 작성됨" affordances.</li>
     * </ul>
     */
    @GetMapping("/{userId}/reflections")
    public ApiResponse<List<ReflectionDto>> reflections(
            Authentication authentication,
            @PathVariable long userId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        User viewer = currentUser.require(authentication);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (!friendService.canView(viewer, target)) {
            throw new ForbiddenException("회고 접근 권한이 없습니다.");
        }
        boolean canSeeBody = viewer.getId().equals(target.getId())
                || roomMembers.existsSharedRoom(viewer, target);
        return ApiResponse.of(dailyService.recentReflections(target, limit).stream()
                .map(r -> ReflectionDto.from(r, canSeeBody))
                .toList());
    }

    public record ProfileDto(long userId, String email, String nickname, String timezone) {
        static ProfileDto from(User user) {
            return new ProfileDto(user.getId(), user.getEmail(), user.getNickname(), user.getTimezone());
        }
    }
    public record PublicProfileDto(long userId, String nickname, String timezone) {
        static PublicProfileDto from(User user) {
            return new PublicProfileDto(user.getId(), user.getNickname(), user.getTimezone());
        }
    }
    public record GrassDayDto(LocalDate date, boolean missionCompleted, int completedTodoCount, boolean reflectionSubmitted, int intensity) {}
    public record StreakDto(long userId, int currentStreak) {}
    public record ReflectionDto(LocalDate date, String body, Instant submittedAt, boolean bodyVisible) {
        static ReflectionDto from(DailyService.ReflectionView r, boolean canSeeBody) {
            return new ReflectionDto(
                    r.date(),
                    canSeeBody ? r.body() : null,
                    r.submittedAt(),
                    canSeeBody
            );
        }
    }
}
