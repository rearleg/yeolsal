package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 5.2 — Mockito unit assertions for {@link RoomMemberCapService}.
 * Nine cases cover the AC4 upsert contract, the AC1 leader/room
 * pre-conditions, and the AC3 calendar-month KST boundary traps.
 */
@ExtendWith(MockitoExtension.class)
class RoomMemberCapServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long ROOM_ID = 42L;
    private static final long LEADER_ID = 7L;
    private static final long STRANGER_ID = 99L;
    private static final Instant DEFAULT_NOW =
            LocalDateTime.of(2026, 4, 15, 12, 0).atZone(KST).toInstant();

    @Mock private RoomService roomService;

    private RoomMemberCapService service;
    private User leader;
    private User stranger;
    private Room room;

    @BeforeEach
    void setUp() {
        service = build(Clock.fixed(DEFAULT_NOW, KST));
        leader = makeUser(LEADER_ID, "leader@example.com", "Leader");
        stranger = makeUser(STRANGER_ID, "stranger@example.com", "Stranger");
        room = makeRoom(ROOM_ID, leader);
    }

    private RoomMemberCapService build(Clock clock) {
        return new RoomMemberCapService(roomService, clock);
    }

    @Test
    @DisplayName("updateMemberCap happy insert — writes pending pair (no prior pending)")
    void updateMemberCap_insertsPending() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        RoomService.RoomSummary summary = service.updateMemberCap(leader, ROOM_ID, 20);

        assertThat(room.getPendingMaxMembers()).isEqualTo((short) 20);
        assertThat(room.getPendingMaxMembersEffectiveFromMonth()).isEqualTo("2026-05");
        assertThat(summary.pendingMaxMembers()).isEqualTo(20);
        assertThat(summary.pendingMaxMembersEffectiveFromMonth()).isEqualTo("2026-05");
        verify(roomService).requireLeader(room, leader);
    }

    @Test
    @DisplayName("updateMemberCap happy replace — overwrites a prior pending value")
    void updateMemberCap_overwritesPriorPending() {
        room.setPendingMaxMembers((short) 15);
        room.setPendingMaxMembersEffectiveFromMonth("2026-05");
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        service.updateMemberCap(leader, ROOM_ID, 25);

        assertThat(room.getPendingMaxMembers()).isEqualTo((short) 25);
        assertThat(room.getPendingMaxMembersEffectiveFromMonth()).isEqualTo("2026-05");
    }

    @Test
    @DisplayName("updateMemberCap leader-only — non-leader → ForbiddenException, no write")
    void updateMemberCap_nonLeader_throwsForbidden() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        doThrow(new ForbiddenException("방장 권한이 필요합니다."))
                .when(roomService).requireLeader(any(Room.class), eq(stranger));

        assertThatThrownBy(() -> service.updateMemberCap(stranger, ROOM_ID, 20))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("방장 권한");

        assertThat(room.getPendingMaxMembers()).isNull();
        assertThat(room.getPendingMaxMembersEffectiveFromMonth()).isNull();
    }

    @Test
    @DisplayName("updateMemberCap unknown room → NotFoundException, no leader check")
    void updateMemberCap_unknownRoom_throwsNotFound() {
        when(roomService.requireRoomForUpdate(ROOM_ID))
                .thenThrow(new NotFoundException("방을 찾을 수 없습니다."));

        assertThatThrownBy(() -> service.updateMemberCap(leader, ROOM_ID, 20))
                .isInstanceOf(NotFoundException.class);

        verify(roomService, never()).requireLeader(any(Room.class), any(User.class));
    }

    @Test
    @DisplayName("updateMemberCap below 2 → BadRequestException")
    void updateMemberCap_belowMin_throwsBadRequest() {
        assertThatThrownBy(() -> service.updateMemberCap(leader, ROOM_ID, 1))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2에서 30 사이");
        verify(roomService, never()).requireRoomForUpdate(ROOM_ID);
    }

    @Test
    @DisplayName("updateMemberCap above 30 → BadRequestException")
    void updateMemberCap_aboveMax_throwsBadRequest() {
        assertThatThrownBy(() -> service.updateMemberCap(leader, ROOM_ID, 31))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2에서 30 사이");
    }

    @Test
    @DisplayName("nextMonth at 2026-04-30 23:59 KST returns \"2026-05\" (calendar-month KST trap #1)")
    void nextMonth_atLateAprilKst_returnsMay() {
        service = build(Clock.fixed(
                LocalDateTime.of(2026, 4, 30, 23, 59, 30).atZone(KST).toInstant(), KST));
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        service.updateMemberCap(leader, ROOM_ID, 20);

        assertThat(room.getPendingMaxMembersEffectiveFromMonth()).isEqualTo("2026-05");
    }

    @Test
    @DisplayName("nextMonth at 2026-05-01 02:00 KST returns \"2026-06\" (calendar already May)")
    void nextMonth_atEarlyMayKst_returnsJune() {
        service = build(Clock.fixed(
                LocalDateTime.of(2026, 5, 1, 2, 0, 0).atZone(KST).toInstant(), KST));
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        service.updateMemberCap(leader, ROOM_ID, 18);

        assertThat(room.getPendingMaxMembersEffectiveFromMonth()).isEqualTo("2026-06");
    }

    @Test
    @DisplayName("updateMemberCap idempotent re-edit — same value + same month is a no-op")
    void updateMemberCap_idempotent() {
        room.setPendingMaxMembers((short) 20);
        room.setPendingMaxMembersEffectiveFromMonth("2026-05");
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        RoomService.RoomSummary summary = service.updateMemberCap(leader, ROOM_ID, 20);

        assertThat(summary.pendingMaxMembers()).isEqualTo(20);
        assertThat(summary.pendingMaxMembersEffectiveFromMonth()).isEqualTo("2026-05");
    }

    // ---------- helpers ----------

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
    }

    private static Room makeRoom(long id, User owner) {
        Room r = new Room("test-room", owner);
        setField(r, "id", id);
        return r;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
