package com.yeosal.api.room;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final RoomInviteRepository roomInvites;
    private final GroupMemberMinimumRepository minimums;
    private final InviteCodeGenerator codeGenerator;
    private final Clock clock;

    public RoomService(
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            RoomInviteRepository roomInvites,
            GroupMemberMinimumRepository minimums,
            InviteCodeGenerator codeGenerator,
            Clock clock
    ) {
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.roomInvites = roomInvites;
        this.minimums = minimums;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    /** Backwards-compatible overload that creates a room with the default minimum. */
    public RoomSummary create(User owner, String name) {
        return create(owner, name, GoalMinimumDays.DEFAULT);
    }

    @Transactional
    public RoomSummary create(User owner, String name, int minDailyGoalDays) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("방 이름은 비어있을 수 없습니다.");
        }
        // Whitelist check runs on the full int so out-of-range values
        // (including ones that would alias to an allowed short) are rejected.
        if (!GoalMinimumDays.isAllowed(minDailyGoalDays)) {
            throw new BadRequestException("최소 목표일수는 10/15/20/매일 중 하나여야 합니다.");
        }
        short min = (short) minDailyGoalDays;
        Room room = rooms.save(new Room(name.trim(), owner, min));
        roomMembers.save(new RoomMember(room, owner, RoomRole.OWNER));
        // Owner's per-member row mirrors the room minimum at creation time.
        minimums.save(new GroupMemberMinimum(room.getId(), owner.getId(), min));
        return RoomSummary.from(room);
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> myRooms(User user) {
        return roomMembers.findByUser(user).stream()
                .map(RoomMember::getRoom)
                .map(RoomSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberSummary> members(User viewer, long roomId) {
        Room room = requireRoom(roomId);
        requireMembership(room, viewer);
        // Batch-load every minimum row for this room so we don't issue one query
        // per member while building the response.
        Map<Long, GroupMemberMinimum> byUserId = new HashMap<>();
        for (GroupMemberMinimum m : minimums.findByRoomId(room.getId())) {
            byUserId.put(m.getUserId(), m);
        }
        return roomMembers.findByRoom(room).stream()
                .map(rm -> MemberSummary.from(rm, byUserId.get(rm.getUser().getId())))
                .toList();
    }

    @Transactional
    public InviteSummary createInvite(User creator, long roomId, Duration ttl) {
        Room room = requireRoom(roomId);
        requireMembership(room, creator);

        String code = codeGenerator.generate(roomInvites::existsByCodeAndRevokedAtIsNull);
        Instant expiresAt = ttl == null ? null : clock.instant().plus(ttl);
        RoomInvite saved = roomInvites.save(new RoomInvite(room, code, creator, expiresAt));
        return InviteSummary.from(saved);
    }

    @Transactional
    public MemberSummary joinByCode(User user, String code) {
        Instant now = clock.instant();
        RoomInvite invite = roomInvites.findActiveByCode(code, now)
                .orElseThrow(() -> new NotFoundException("초대 코드를 찾을 수 없습니다."));
        Room room = invite.getRoom();

        Optional<RoomMember> existing = roomMembers.findByRoomAndUser(room, user);
        if (existing.isPresent()) {
            GroupMemberMinimum existingMin =
                    minimums.findByRoomIdAndUserId(room.getId(), user.getId()).orElse(null);
            return MemberSummary.from(existing.get(), existingMin);
        }

        long memberCount = roomMembers.countByRoom(room);
        if (memberCount >= room.getMaxMembers()) {
            throw new BadRequestException("방 정원을 초과했습니다.");
        }
        RoomMember saved = roomMembers.save(new RoomMember(room, user, RoomRole.MEMBER));
        // New members start by mirroring the room's current minimum. They can
        // raise (but not lower) their own minimum later via the PATCH endpoint
        // introduced in PR E.
        GroupMemberMinimum minimum = minimums.save(new GroupMemberMinimum(
                room.getId(), user.getId(), room.getMinDailyGoalDays()));
        return MemberSummary.from(saved, minimum);
    }

    /**
     * Member-driven raise of their per-(room, user) minimum. The new value
     * must be in the global whitelist {@link GoalMinimumDays#ALLOWED} *and*
     * at least the room's current minimum — members can never set themselves
     * below the room-wide floor. Lowering one's own minimum below the floor
     * would defeat the room's social contract; raising is fine and intended.
     */
    @Transactional
    public MemberSummary updateMyMinimum(User user, long roomId, int minDailyGoalDays) {
        Room room = requireRoom(roomId);
        RoomMember membership = requireMembership(room, user);
        if (!GoalMinimumDays.isAllowed(minDailyGoalDays)) {
            throw new BadRequestException("최소 목표일수는 10/15/20/매일 중 하나여야 합니다.");
        }
        if (minDailyGoalDays < room.getMinDailyGoalDays()) {
            throw new BadRequestException(
                    "그룹 최소 기준(" + room.getMinDailyGoalDays() + "일) 이상으로만 설정할 수 있습니다.");
        }
        GroupMemberMinimum minimum = minimums
                .findByRoomIdAndUserId(room.getId(), user.getId())
                .orElseGet(() -> {
                    // Race-safe lazy create — concurrent PATCHes for a missing
                    // row would otherwise collide on the UNIQUE constraint.
                    minimums.insertIfAbsent(
                            room.getId(), user.getId(), room.getMinDailyGoalDays());
                    return minimums.findByRoomIdAndUserId(room.getId(), user.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "group_member_minimums row missing after upsert"));
                });
        minimum.setMinDailyGoalDays((short) minDailyGoalDays);
        // No explicit save — JPA dirty-check on the @Transactional commit. The
        // entity's @PreUpdate touches updated_at.
        return MemberSummary.from(membership, minimum);
    }

    @Transactional
    public void leave(User user, long roomId) {
        Room room = requireRoom(roomId);
        RoomMember membership = roomMembers.findByRoomAndUser(room, user)
                .orElseThrow(() -> new NotFoundException("방 멤버가 아닙니다."));

        if (membership.getRole() == RoomRole.OWNER) {
            long count = roomMembers.countByRoom(room);
            if (count > 1) {
                throw new BadRequestException(
                        "owner는 다른 멤버가 남아있는 동안 방을 떠날 수 없습니다.");
            }
            // group_member_minimums(room_id, user_id) FK has ON DELETE CASCADE
            // against room_members, so deleting the last RoomMember reaps the
            // matching minimum row automatically.
            roomMembers.delete(membership);
            rooms.delete(room);
            return;
        }
        roomMembers.delete(membership);
    }

    private Room requireRoom(long roomId) {
        return rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
    }

    private RoomMember requireMembership(Room room, User user) {
        return roomMembers.findByRoomAndUser(room, user)
                .orElseThrow(() -> new ForbiddenException("방 멤버만 접근할 수 있습니다."));
    }

    public record RoomSummary(long id, String name, long ownerId, int maxMembers, int minDailyGoalDays) {
        public static RoomSummary from(Room room) {
            return new RoomSummary(
                    room.getId(),
                    room.getName(),
                    room.getOwner().getId(),
                    room.getMaxMembers(),
                    room.getMinDailyGoalDays()
            );
        }
    }

    public record MemberSummary(
            long roomId,
            long userId,
            String nickname,
            RoomRole role,
            int currentMinimum,
            int warningCount
    ) {
        public static MemberSummary from(RoomMember m, GroupMemberMinimum minimum) {
            int min = minimum != null ? minimum.getMinDailyGoalDays() : m.getRoom().getMinDailyGoalDays();
            int warnings = minimum != null ? minimum.getWarningCount() : 0;
            return new MemberSummary(
                    m.getRoom().getId(),
                    m.getUser().getId(),
                    m.getUser().getNickname(),
                    m.getRole(),
                    min,
                    warnings
            );
        }
    }

    public record InviteSummary(long id, long roomId, String code, Instant expiresAt) {
        public static InviteSummary from(RoomInvite invite) {
            return new InviteSummary(
                    invite.getId(),
                    invite.getRoom().getId(),
                    invite.getCode(),
                    invite.getExpiresAt()
            );
        }
    }
}
