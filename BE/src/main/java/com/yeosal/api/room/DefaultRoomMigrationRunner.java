package com.yeosal.api.room;

import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.survival.RoomRuleVersionRepository;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot seeder that groups existing accepted-friendship connected components
 * into "기본 방" rooms when the application boots for the first time after the
 * V3 migration. Idempotent: skips entirely once any room exists.
 *
 * <p>Components of size 1 are skipped (a solo room is meaningless); components
 * larger than {@code MAX_DEFAULT_ROOM_SIZE} are skipped with a warning so an
 * operator can curate them manually rather than getting an arbitrary truncation.
 */
@Component
public class DefaultRoomMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultRoomMigrationRunner.class);
    private static final int MAX_DEFAULT_ROOM_SIZE = 8;
    private static final String DEFAULT_ROOM_NAME = "기본 방";

    private final FriendshipRepository friendships;
    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final UserRepository users;
    private final SurvivalStateService survivalStateService;
    private final RoomRuleVersionRepository roomRuleVersions;
    private final Clock clock;

    public DefaultRoomMigrationRunner(
            FriendshipRepository friendships,
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            UserRepository users,
            SurvivalStateService survivalStateService,
            RoomRuleVersionRepository roomRuleVersions,
            Clock clock
    ) {
        this.friendships = friendships;
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.users = users;
        this.survivalStateService = survivalStateService;
        this.roomRuleVersions = roomRuleVersions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (rooms.count() > 0) {
            return;
        }
        List<Friendship> accepted = friendships.findByStatus(FriendshipStatus.ACCEPTED);
        if (accepted.isEmpty()) {
            return;
        }

        Map<Long, Set<Long>> adjacency = buildAdjacency(accepted);
        List<SortedSet<Long>> components = ConnectedComponents.find(adjacency);

        int seeded = 0;
        for (SortedSet<Long> component : components) {
            if (component.size() < 2) {
                continue;
            }
            if (component.size() > MAX_DEFAULT_ROOM_SIZE) {
                log.warn("Skipping default-room seed for component of size {} (members={}). "
                                + "Operator must curate this group manually.",
                        component.size(), component);
                continue;
            }
            seedRoom(component);
            seeded++;
        }
        if (seeded > 0) {
            log.info("Seeded {} default room(s) from {} accepted friendship(s).", seeded, accepted.size());
        }
    }

    private Map<Long, Set<Long>> buildAdjacency(List<Friendship> accepted) {
        Map<Long, Set<Long>> adjacency = new HashMap<>();
        for (Friendship f : accepted) {
            Long a = f.getRequester().getId();
            Long b = f.getAddressee().getId();
            adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }
        return adjacency;
    }

    private void seedRoom(SortedSet<Long> memberIds) {
        Long ownerId = memberIds.first();
        User owner = users.findById(ownerId).orElse(null);
        if (owner == null) {
            log.warn("Default-room seed skipped: owner user id={} not found.", ownerId);
            return;
        }
        // Keep every membership and survival-state window anchored to one instant.
        Instant now = clock.instant();
        Room room = rooms.save(new Room(DEFAULT_ROOM_NAME, owner));
        roomRuleVersions.insertDefaultIfAbsent(
                room.getId(),
                YearMonth.from(LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul"))).toString(),
                owner.getId());
        RoomMember ownerMember = new RoomMember(room, owner, RoomRole.OWNER);
        ownerMember.setJoinedAt(now);
        RoomMember savedOwner = roomMembers.save(ownerMember);
        // Pair the survival_state row with each membership inside the same
        // @Transactional boundary so the auto-seeded rooms satisfy the same
        // (room_members, survival_state) atomicity invariant as RoomService.create.
        survivalStateService.initializeOnJoin(room, owner, savedOwner.getJoinedAt());
        for (Long memberId : memberIds) {
            if (memberId.equals(ownerId)) continue;
            users.findById(memberId).ifPresent(member -> {
                RoomMember rm = new RoomMember(room, member, RoomRole.MEMBER);
                rm.setJoinedAt(now);
                RoomMember savedMember = roomMembers.save(rm);
                survivalStateService.initializeOnJoin(room, member, savedMember.getJoinedAt());
            });
        }
    }
}
