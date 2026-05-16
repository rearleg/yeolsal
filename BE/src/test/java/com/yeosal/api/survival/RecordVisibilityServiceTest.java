package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mockito unit tests for {@link RecordVisibilityService} (Story 2.3 AC1/AC2 + AC10).
 *
 * <p>Pins the AC6 default-off contract (materialize {@code false} for rooms
 * without rows), the AC2 membership-guard 403, and the upsert re-read flow
 * that surfaces the post-write {@code updated_at}.
 */
class RecordVisibilityServiceTest {

    private RecordVisibilityPrefRepository prefs;
    private RoomMemberRepository roomMembers;
    private RecordVisibilityService service;

    private User alice;
    private Room roomA;
    private Room roomB;

    @BeforeEach
    void setUp() {
        prefs = mock(RecordVisibilityPrefRepository.class);
        roomMembers = mock(RoomMemberRepository.class);
        service = new RecordVisibilityService(prefs, roomMembers);

        alice = makeUser(1L, "alice@example.com", "Alice");
        roomA = makeRoom(10L, "팀 A", alice);
        roomB = makeRoom(11L, "팀 B", alice);
    }

    @Test
    @DisplayName("listForUser materializes false-default for rooms without an explicit row")
    void listForUser_materializes_default_false() {
        RoomMember mA = new RoomMember(roomA, alice, RoomRole.MEMBER);
        RoomMember mB = new RoomMember(roomB, alice, RoomRole.MEMBER);
        when(roomMembers.findByUser(alice)).thenReturn(List.of(mA, mB));
        Instant updatedAt = Instant.parse("2026-05-16T01:23:45Z");
        RecordVisibilityPref rowA = makePref(1L, 10L, true, updatedAt);
        when(prefs.findByUserId(1L)).thenReturn(List.of(rowA));

        List<VisibilityPrefDto> result = service.listForUser(alice);

        assertThat(result).hasSize(2);
        VisibilityPrefDto a = result.get(0);
        VisibilityPrefDto b = result.get(1);
        assertThat(a.roomId()).isEqualTo(10L);
        assertThat(a.roomName()).isEqualTo("팀 A");
        assertThat(a.shareOnElimination()).isTrue();
        assertThat(a.updatedAt()).isEqualTo(updatedAt);
        assertThat(b.roomId()).isEqualTo(11L);
        assertThat(b.roomName()).isEqualTo("팀 B");
        assertThat(b.shareOnElimination()).isFalse();
        assertThat(b.updatedAt()).isNull();
    }

    @Test
    @DisplayName("upsert calls the native upsert and re-reads the row for updated_at")
    void upsert_calls_upsert_then_reread() {
        when(roomMembers.existsByRoomIdAndUserId(10L, 1L)).thenReturn(true);
        Instant updatedAt = Instant.parse("2026-05-16T01:00:00Z");
        RecordVisibilityPref row = makePref(1L, 10L, true, updatedAt);
        when(prefs.findByUserIdAndRoomId(1L, 10L)).thenReturn(Optional.of(row));
        when(roomMembers.findByUser(alice)).thenReturn(
                List.of(new RoomMember(roomA, alice, RoomRole.MEMBER)));

        VisibilityPrefDto dto = service.upsert(alice, 10L, true);

        verify(prefs).upsertShareOnElimination(1L, 10L, true);
        verify(prefs).findByUserIdAndRoomId(1L, 10L);
        assertThat(dto.shareOnElimination()).isTrue();
        assertThat(dto.updatedAt()).isEqualTo(updatedAt);
        assertThat(dto.roomName()).isEqualTo("팀 A");
    }

    @Test
    @DisplayName("upsert for a non-member room throws ForbiddenException without touching the DB")
    void upsert_nonMember_throwsForbidden() {
        when(roomMembers.existsByRoomIdAndUserId(99L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.upsert(alice, 99L, true))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("멤버가 아닙니다");

        verify(prefs, never()).upsertShareOnElimination(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("idempotent re-upsert (same value twice) does not throw and re-reads each time")
    void upsert_idempotent_reupsert() {
        when(roomMembers.existsByRoomIdAndUserId(10L, 1L)).thenReturn(true);
        Instant first = Instant.parse("2026-05-16T01:00:00Z");
        Instant second = Instant.parse("2026-05-16T02:00:00Z");
        RecordVisibilityPref rowFirst = makePref(1L, 10L, true, first);
        RecordVisibilityPref rowSecond = makePref(1L, 10L, true, second);
        when(prefs.findByUserIdAndRoomId(1L, 10L))
                .thenReturn(Optional.of(rowFirst))
                .thenReturn(Optional.of(rowSecond));
        when(roomMembers.findByUser(alice)).thenReturn(
                List.of(new RoomMember(roomA, alice, RoomRole.MEMBER)));

        VisibilityPrefDto a = service.upsert(alice, 10L, true);
        VisibilityPrefDto b = service.upsert(alice, 10L, true);

        verify(prefs, times(2)).upsertShareOnElimination(1L, 10L, true);
        assertThat(a.updatedAt()).isEqualTo(first);
        assertThat(b.updatedAt()).isEqualTo(second);
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        return setId(u, id);
    }

    private static Room makeRoom(long id, String name, User owner) {
        return setId(new Room(name, owner), id);
    }

    private static RecordVisibilityPref makePref(
            long userId, long roomId, boolean share, Instant updatedAt) {
        RecordVisibilityPref pref = new RecordVisibilityPref(userId, roomId, share);
        try {
            Field f = RecordVisibilityPref.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.set(pref, updatedAt);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return pref;
    }

    private static <T> T setId(T entity, long id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return entity;
    }
}
