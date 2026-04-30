package com.yeosal.api.room;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final RoomInviteRepository roomInvites;
    private final InviteCodeGenerator codeGenerator;
    private final Clock clock;

    public RoomService(
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            RoomInviteRepository roomInvites,
            InviteCodeGenerator codeGenerator,
            Clock clock
    ) {
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.roomInvites = roomInvites;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    @Transactional
    public Room create(User owner, String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("방 이름은 비어있을 수 없습니다.");
        }
        Room room = rooms.save(new Room(name.trim(), owner));
        roomMembers.save(new RoomMember(room, owner, RoomRole.OWNER));
        return room;
    }

    @Transactional(readOnly = true)
    public List<Room> myRooms(User user) {
        return roomMembers.findByUser(user).stream()
                .map(RoomMember::getRoom)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoomMember> members(User viewer, long roomId) {
        Room room = requireRoom(roomId);
        requireMembership(room, viewer);
        return roomMembers.findByRoom(room);
    }

    @Transactional
    public RoomInvite createInvite(User creator, long roomId, Duration ttl) {
        Room room = requireRoom(roomId);
        requireMembership(room, creator);

        String code = codeGenerator.generate(roomInvites::existsByCodeAndRevokedAtIsNull);
        Instant expiresAt = ttl == null ? null : clock.instant().plus(ttl);
        return roomInvites.save(new RoomInvite(room, code, creator, expiresAt));
    }

    @Transactional
    public RoomMember joinByCode(User user, String code) {
        Instant now = clock.instant();
        RoomInvite invite = roomInvites.findActiveByCode(code, now)
                .orElseThrow(() -> new NotFoundException("초대 코드를 찾을 수 없습니다."));
        Room room = invite.getRoom();

        Optional<RoomMember> existing = roomMembers.findByRoomAndUser(room, user);
        if (existing.isPresent()) {
            return existing.get();
        }

        long memberCount = roomMembers.countByRoom(room);
        if (memberCount >= room.getMaxMembers()) {
            throw new BadRequestException("방 정원을 초과했습니다.");
        }
        return roomMembers.save(new RoomMember(room, user, RoomRole.MEMBER));
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
}
