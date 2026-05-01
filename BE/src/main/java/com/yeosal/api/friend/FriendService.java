package com.yeosal.api.friend;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.daily.TodoItem;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendService {
    private static final int STREAK_WINDOW_DAYS = 365;

    private final FriendshipRepository friendships;
    private final UserRepository users;
    private final DailyEntryRepository dailyEntries;
    private final DailyService dailyService;
    private final RoomMemberRepository roomMembers;

    public FriendService(
            FriendshipRepository friendships,
            UserRepository users,
            DailyEntryRepository dailyEntries,
            @Lazy DailyService dailyService,
            RoomMemberRepository roomMembers
    ) {
        this.friendships = friendships;
        this.users = users;
        this.dailyEntries = dailyEntries;
        this.dailyService = dailyService;
        this.roomMembers = roomMembers;
    }

    @Transactional(readOnly = true)
    public List<FriendController.FriendDto> friends(User user) {
        return friendships.findByUserAndStatus(user, FriendshipStatus.ACCEPTED).stream()
                .map(friendship -> toFriendDto(other(friendship, user)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FriendController.FriendRequestDto> requests(User user) {
        return friendships.findByAddresseeAndStatus(user, FriendshipStatus.PENDING).stream()
                .map(this::toRequestDto)
                .toList();
    }

    @Transactional
    public FriendController.FriendRequestDto request(User user, FriendController.FriendRequestCreate request) {
        User target = users.findByEmail(request.targetEmail().toLowerCase())
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (target.getId().equals(user.getId())) {
            throw new BadRequestException("자기 자신에게 친구 요청을 보낼 수 없습니다.");
        }
        friendships.findBetween(user, target).ifPresent(existing -> {
            throw new BadRequestException("이미 친구 요청 또는 친구 관계가 있습니다.");
        });
        return toRequestDto(friendships.save(new Friendship(user, target)));
    }

    @Transactional
    public FriendController.FriendRequestDto respond(User user, long id, FriendController.FriendRequestDecision request) {
        Friendship friendship = friendships.findById(id)
                .orElseThrow(() -> new NotFoundException("친구 요청을 찾을 수 없습니다."));
        if (!friendship.getAddressee().getId().equals(user.getId())) {
            throw new ForbiddenException("친구 요청 응답 권한이 없습니다.");
        }
        friendship.setStatus(request.accepted() ? FriendshipStatus.ACCEPTED : FriendshipStatus.DECLINED);
        return toRequestDto(friendship);
    }

    @Transactional(readOnly = true)
    public List<FriendController.DailyFeedItem> dailyFeed(User user, LocalDate date) {
        List<User> friendUsers = friendships.findByUserAndStatus(user, FriendshipStatus.ACCEPTED).stream()
                .map(friendship -> other(friendship, user))
                .toList();
        if (friendUsers.isEmpty()) {
            return List.of();
        }
        List<DailyEntry> todayEntries = dailyEntries.findByUserInAndDate(friendUsers, date);

        // Batch fetch the streak window (todos + reflection eagerly loaded) for
        // ALL friends in one query, then group by user. Replaces the per-friend
        // currentStreak() loop that triggered O(friends * windowDays) lazy
        // todos fetches in the previous implementation.
        LocalDate streakFrom = date.minusDays(STREAK_WINDOW_DAYS - 1L);
        List<DailyEntry> streakEntries = dailyEntries.findGrassEntriesByUsersBetween(friendUsers, streakFrom, date);
        Map<Long, List<DailyEntry>> entriesByUser = streakEntries.stream()
                .collect(Collectors.groupingBy(e -> e.getUser().getId()));

        return friendUsers.stream()
                .map(friend -> {
                    List<DailyEntry> friendEntries = entriesByUser.getOrDefault(friend.getId(), List.of());
                    List<GrassDay> window = dailyService.grassFromEntries(friend, streakFrom, date, friendEntries);
                    int streak = dailyService.streakFromGrass(date, window);
                    return todayEntries.stream()
                            .filter(entry -> entry.getUser().getId().equals(friend.getId()))
                            .findFirst()
                            .map(entry -> new FriendController.DailyFeedItem(
                                    friend.getId(),
                                    friend.getNickname(),
                                    date,
                                    entry.getGoal(),
                                    (int) entry.getTodos().stream().filter(TodoItem::isCompleted).count(),
                                    entry.getReflection() != null,
                                    streak
                            ))
                            .orElse(new FriendController.DailyFeedItem(friend.getId(), friend.getNickname(), date, "", 0, false, streak));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean canView(User viewer, User target) {
        if (viewer.getId().equals(target.getId())) {
            return true;
        }
        boolean acceptedFriendship = friendships.findBetween(viewer, target)
                .filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                .isPresent();
        if (acceptedFriendship) {
            return true;
        }
        return roomMembers.existsSharedRoom(viewer, target);
    }

    private User other(Friendship friendship, User user) {
        return friendship.getRequester().getId().equals(user.getId()) ? friendship.getAddressee() : friendship.getRequester();
    }

    private FriendController.FriendDto toFriendDto(User user) {
        return new FriendController.FriendDto(user.getId(), user.getNickname(), "ACCEPTED");
    }

    private FriendController.FriendRequestDto toRequestDto(Friendship friendship) {
        return new FriendController.FriendRequestDto(
                friendship.getId(),
                friendship.getRequester().getEmail(),
                friendship.getRequester().getNickname(),
                friendship.getStatus().name()
        );
    }
}
