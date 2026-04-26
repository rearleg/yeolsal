package com.yeosal.api.friend;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.TodoItem;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendService {
    private final FriendshipRepository friendships;
    private final UserRepository users;
    private final DailyEntryRepository dailyEntries;

    public FriendService(FriendshipRepository friendships, UserRepository users, DailyEntryRepository dailyEntries) {
        this.friendships = friendships;
        this.users = users;
        this.dailyEntries = dailyEntries;
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
        List<DailyEntry> entries = dailyEntries.findByUserInAndDate(friendUsers, date);
        return friendUsers.stream()
                .map(friend -> entries.stream()
                        .filter(entry -> entry.getUser().getId().equals(friend.getId()))
                        .findFirst()
                        .map(entry -> new FriendController.DailyFeedItem(
                                friend.getId(),
                                friend.getNickname(),
                                date,
                                entry.getGoal(),
                                (int) entry.getTodos().stream().filter(TodoItem::isCompleted).count(),
                                entry.getReflection() != null
                        ))
                        .orElse(new FriendController.DailyFeedItem(friend.getId(), friend.getNickname(), date, "", 0, false)))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean canView(User viewer, User target) {
        return viewer.getId().equals(target.getId()) ||
                friendships.findBetween(viewer, target).filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED).isPresent();
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
