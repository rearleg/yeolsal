package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Story 5.2 — Mockito unit assertions for {@link TransferLeadershipService}.
 * Eleven cases cover the AC6 10-step flow, the AC10 INELIGIBLE_LEADER
 * exception path, and the AC9 realtime emission contract.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferLeadershipServiceTest {

    private static final long ROOM_ID = 42L;
    private static final long LEADER_ID = 1L;
    private static final long TARGET_ID = 2L;
    private static final long STRANGER_ID = 99L;

    @Mock private RoomService roomService;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private SurvivalStateRepository survivalStates;
    @Mock private UserRepository users;
    @Mock private RealtimePublisher realtime;

    private TransferLeadershipService service;
    private User leader;
    private User target;
    private User stranger;
    private Room room;
    private RoomMember leaderMember;
    private RoomMember targetMember;

    @BeforeEach
    void setUp() {
        service = new TransferLeadershipService(
                roomService, roomMembers, survivalStates, users, realtime);
        leader = makeUser(LEADER_ID, "leader@example.com", "Leader");
        target = makeUser(TARGET_ID, "target@example.com", "Target");
        stranger = makeUser(STRANGER_ID, "stranger@example.com", "Stranger");
        room = makeRoom(ROOM_ID, leader);
        leaderMember = new RoomMember(room, leader, RoomRole.OWNER);
        targetMember = new RoomMember(room, target, RoomRole.MEMBER);
    }

    @Test
    @DisplayName("transferLeadership happy — target ACTIVE: owner flips + both roles flip + publish")
    void transfer_happyActive_flipsAllThreeRows() {
        stubLoad(SurvivalStatus.ACTIVE);

        RoomService.RoomSummary summary = service.transferLeadership(leader, ROOM_ID, TARGET_ID);

        assertThat(room.getOwner().getId()).isEqualTo(TARGET_ID);
        assertThat(targetMember.getRole()).isEqualTo(RoomRole.OWNER);
        assertThat(leaderMember.getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(summary.ownerId()).isEqualTo(TARGET_ID);

        ArgumentCaptor<LeadershipChangePayload> payloadCaptor =
                ArgumentCaptor.forClass(LeadershipChangePayload.class);
        verify(realtime).publishLeadershipChange(eq(ROOM_ID), payloadCaptor.capture());
        LeadershipChangePayload payload = payloadCaptor.getValue();
        assertThat(payload.roomId()).isEqualTo(ROOM_ID);
        assertThat(payload.previousLeaderUserId()).isEqualTo(LEADER_ID);
        assertThat(payload.newLeaderUserId()).isEqualTo(TARGET_ID);
        assertThat(payload.reason()).isEqualTo("MANUAL_TRANSFER");
        verify(roomService).requireRoomForUpdate(ROOM_ID);
        verify(survivalStates).findByRoomIdAndUserIdForUpdate(ROOM_ID, TARGET_ID);
    }

    @Test
    @DisplayName("transferLeadership happy — target YELLOW also flips (pre-elimination eligible)")
    void transfer_happyYellow_flipsAllThreeRows() {
        stubLoad(SurvivalStatus.YELLOW);

        service.transferLeadership(leader, ROOM_ID, TARGET_ID);

        assertThat(room.getOwner().getId()).isEqualTo(TARGET_ID);
        assertThat(targetMember.getRole()).isEqualTo(RoomRole.OWNER);
        verify(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));
    }

    @Test
    @DisplayName("transferLeadership non-leader → ForbiddenException, no flip, no publish")
    void transfer_nonLeader_throwsForbidden() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        doThrow(new ForbiddenException("방장 권한이 필요합니다."))
                .when(roomService).requireLeader(any(Room.class), eq(stranger));

        assertThatThrownBy(() -> service.transferLeadership(stranger, ROOM_ID, TARGET_ID))
                .isInstanceOf(ForbiddenException.class);

        assertThat(room.getOwner().getId()).isEqualTo(LEADER_ID);
        verifyNoInteractions(realtime);
    }

    @Test
    @DisplayName("transferLeadership unknown room → NotFoundException, no publish")
    void transfer_unknownRoom_throwsNotFound() {
        when(roomService.requireRoomForUpdate(ROOM_ID))
                .thenThrow(new NotFoundException("방을 찾을 수 없습니다."));

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, TARGET_ID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(realtime);
    }

    @Test
    @DisplayName("transferLeadership target user not found → BadRequestException")
    void transfer_targetUserNotFound_throwsBadRequest() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(users.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, TARGET_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("대상 사용자");
        verifyNoInteractions(realtime);
    }

    @Test
    @DisplayName("transferLeadership target not a member → BadRequestException")
    void transfer_targetNotMember_throwsBadRequest() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(users.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(roomMembers.findByRoomAndUser(room, target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, TARGET_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("방의 멤버");
    }

    @Test
    @DisplayName("transferLeadership self-transfer → BadRequestException BEFORE membership lookup")
    void transfer_selfTransfer_throwsBadRequest() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, LEADER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("본인이 방장");

        verify(users, never()).findById(LEADER_ID);
        verifyNoInteractions(realtime);
    }

    @Test
    @DisplayName("transferLeadership target RED → IneligibleLeaderException (409 INELIGIBLE_LEADER)")
    void transfer_targetRed_throwsIneligible() {
        stubLoadWithoutSurvival();
        when(survivalStates.findByRoomIdAndUserIdForUpdate(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(makeSurvivalState(SurvivalStatus.RED)));

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, TARGET_ID))
                .isInstanceOf(IneligibleLeaderException.class);

        assertThat(room.getOwner().getId()).isEqualTo(LEADER_ID);
        verifyNoInteractions(realtime);
    }

    @Test
    @DisplayName("transferLeadership target SPECTATOR → IneligibleLeaderException")
    void transfer_targetSpectator_throwsIneligible() {
        stubLoadWithoutSurvival();
        when(survivalStates.findByRoomIdAndUserIdForUpdate(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(makeSurvivalState(SurvivalStatus.SPECTATOR)));

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, TARGET_ID))
                .isInstanceOf(IneligibleLeaderException.class);
    }

    @Test
    @DisplayName("transferLeadership target missing survival row → IneligibleLeaderException (defensive)")
    void transfer_targetNoSurvivalRow_throwsIneligible() {
        stubLoadWithoutSurvival();
        when(survivalStates.findByRoomIdAndUserIdForUpdate(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferLeadership(leader, ROOM_ID, TARGET_ID))
                .isInstanceOf(IneligibleLeaderException.class);
    }

    @Test
    @DisplayName("realtime publish runs once (direct call when no tx synchronization is active)")
    void transfer_publishCalledOnce_perCommit() {
        stubLoad(SurvivalStatus.ACTIVE);

        service.transferLeadership(leader, ROOM_ID, TARGET_ID);

        verify(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));
    }

    @Test
    @DisplayName("realtime publish is deferred until afterCommit when tx synchronization is active")
    void transfer_publishDeferredUntilAfterCommit() {
        stubLoad(SurvivalStatus.ACTIVE);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.transferLeadership(leader, ROOM_ID, TARGET_ID);

            verifyNoInteractions(realtime);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));
    }

    @Test
    @DisplayName("realtime publish is skipped when tx synchronization clears without afterCommit")
    void transfer_publishSkippedOnRollback() {
        stubLoad(SurvivalStatus.ACTIVE);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.transferLeadership(leader, ROOM_ID, TARGET_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verifyNoInteractions(realtime);
    }

    // ---------- helpers ----------

    private void stubLoad(SurvivalStatus targetStatus) {
        stubLoadWithoutSurvival();
        when(survivalStates.findByRoomIdAndUserIdForUpdate(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(makeSurvivalState(targetStatus)));
    }

    private void stubLoadWithoutSurvival() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(users.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(roomMembers.findByRoomAndUser(room, target)).thenReturn(Optional.of(targetMember));
        when(roomMembers.findByRoomAndUser(room, leader)).thenReturn(Optional.of(leaderMember));
    }

    private SurvivalState makeSurvivalState(SurvivalStatus status) {
        SurvivalState s = new SurvivalState(room, target, Instant.parse("2026-04-29T15:00:00Z"));
        setField(s, "status", status);
        return s;
    }

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
