package com.yeosal.api.survival;

import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.User;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-(user, room) record-visibility opt-in service (Story 2.3 AC1/AC2).
 *
 * <p>Default is {@code false} — a missing row is treated identically to
 * a row with {@code share_on_elimination = false}. {@link #listForUser}
 * materializes the default for every room the user is a member of so the
 * Settings screen never has to default-construct on the FE.
 *
 * <p>{@link #upsert} guards on room membership: a viewer cannot set a pref
 * for a room they don't belong to (defense-in-depth — the FE only renders
 * the toggle on the per-room settings screen, but this fixes the contract
 * if someone POSTs the endpoint directly).
 */
@Service
public class RecordVisibilityService {

    private final RecordVisibilityPrefRepository prefs;
    private final RoomMemberRepository roomMembers;

    public RecordVisibilityService(
            RecordVisibilityPrefRepository prefs, RoomMemberRepository roomMembers) {
        this.prefs = prefs;
        this.roomMembers = roomMembers;
    }

    /**
     * List the user's visibility prefs across every room they're a member
     * of. Materializes the default ({@code false}) for rooms without an
     * explicit row — the FE never has to know about the lazy-row policy.
     */
    @Transactional(readOnly = true)
    public List<VisibilityPrefDto> listForUser(User user) {
        long userId = user.getId();
        List<RoomMember> memberships = roomMembers.findByUser(user);
        Map<Long, RecordVisibilityPref> byRoomId = new HashMap<>();
        for (RecordVisibilityPref row : prefs.findByUserId(userId)) {
            byRoomId.put(row.getRoomId(), row);
        }
        return memberships.stream()
                .map(rm -> {
                    Room room = rm.getRoom();
                    RecordVisibilityPref row = byRoomId.get(room.getId());
                    boolean share = row != null && row.isShareOnElimination();
                    Instant updatedAt = row != null ? row.getUpdatedAt() : null;
                    return new VisibilityPrefDto(room.getId(), room.getName(), share, updatedAt);
                })
                .toList();
    }

    /**
     * Upsert the user's pref for one room. Throws {@link ForbiddenException}
     * if the user is not a member of that room (defense-in-depth — see
     * class javadoc).
     */
    @Transactional
    public VisibilityPrefDto upsert(User user, long roomId, boolean shareOnElimination) {
        long userId = user.getId();
        if (!roomMembers.existsByRoomIdAndUserId(roomId, userId)) {
            throw new ForbiddenException("해당 그룹의 멤버가 아닙니다.");
        }
        prefs.upsertShareOnElimination(userId, roomId, shareOnElimination);
        RecordVisibilityPref row = prefs.findByUserIdAndRoomId(userId, roomId)
                .orElseThrow(() -> new IllegalStateException(
                        "record_visibility_prefs upsert succeeded but re-read failed: "
                                + "userId=" + userId + " roomId=" + roomId));
        String roomName = roomMembers.findByUser(user).stream()
                .map(RoomMember::getRoom)
                .filter(r -> r.getId().equals(roomId))
                .map(Room::getName)
                .findFirst()
                .orElse("");
        return new VisibilityPrefDto(
                row.getRoomId(), roomName, row.isShareOnElimination(), row.getUpdatedAt());
    }
}
