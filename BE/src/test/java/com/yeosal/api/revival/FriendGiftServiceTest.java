package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateTransitionEvent;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 3.2 AC12 — Mockito unit tests for {@link RevivalService#reviveFriend}.
 * Covers the 14-case matrix enumerated at story file lines 256-269.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FriendGiftServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-18T03:14:15Z");
    private static final Instant ELIMINATED_AT = Instant.parse("2026-05-17T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private static final long ROOM_ID = 42L;
    private static final long GIVER_ID = 7L;
    private static final long RECEIVER_ID = 11L;
    private static final long OWNER_ID = 1L;
    private static final long REVIVAL_EVENT_ID = 9002L;

    @Mock private SurvivalStateRepository survivalStates;
    @Mock private RoomRepository rooms;
    @Mock private UserRepository users;
    @Mock private RevivalEventRepository revivalEvents;
    @Mock private PersonalPointsLedgerRepository personalLedger;
    @Mock private RoomPointPoolRepository roomPointPool;
    @Mock private FriendshipRepository friendships;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query advisoryLockQuery;

    private RevivalService service;
    private User giver;
    private User receiver;
    private User owner;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new RevivalService(
                survivalStates, rooms, users, revivalEvents, personalLedger,
                roomPointPool, friendships, roomMembers,
                eventPublisher, entityManager, CLOCK);

        giver = makeUser(GIVER_ID, "giver@example.com", "Giver");
        receiver = makeUser(RECEIVER_ID, "receiver@example.com", "Receiver");
        owner = makeUser(OWNER_ID, "owner@example.com", "Owner");
        room = makeRoom(ROOM_ID, "Room", owner);

        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, GIVER_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, RECEIVER_ID)).thenReturn(true);
        when(users.findById(RECEIVER_ID)).thenReturn(Optional.of(receiver));
        when(friendships.findBetween(giver, receiver))
                .thenReturn(Optional.of(makeFriendship(FriendshipStatus.ACCEPTED)));

        when(entityManager.createNativeQuery(any(String.class))).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.setParameter(any(String.class), any())).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.getSingleResult()).thenReturn(1L);

        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    @Test
    @DisplayName("happy — RED, ACCEPTED friend, balance=5 → DTO + ledger -5 + pool +5 + 3 events; lifetime-1=true")
    void reviveFriend_happy_freshGiver() {
        primeHappyPath(/* balance = */ 5);
        when(revivalEvents.existsFriendGiftSendByGiver(GIVER_ID, REVIVAL_EVENT_ID))
                .thenReturn(false);

        FriendGiftRevivalDto dto = service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null);

        assertThat(dto.revivalEventId()).isEqualTo(REVIVAL_EVENT_ID);
        assertThat(dto.source()).isEqualTo("FRIEND_GIFT");
        assertThat(dto.pointsSpent()).isEqualTo(5);
        assertThat(dto.roomPointPoolAfter()).isEqualTo(5);
        assertThat(dto.isFirstEverFriendGiftSend()).isTrue();
        assertThat(dto.receiverNickname()).isEqualTo("Receiver");

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(3)).publishEvent(events.capture());
        assertThat(events.getAllValues().get(0)).isInstanceOf(SurvivalStateTransitionEvent.class);
        SurvivalStateTransitionEvent transition =
                (SurvivalStateTransitionEvent) events.getAllValues().get(0);
        assertThat(transition.userId()).isEqualTo(RECEIVER_ID);
        assertThat(transition.toStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(events.getAllValues().get(1)).isInstanceOf(PointPoolChangeEvent.class);
        assertThat(events.getAllValues().get(2)).isInstanceOf(FriendGiftSentEvent.class);
        FriendGiftSentEvent sent = (FriendGiftSentEvent) events.getAllValues().get(2);
        assertThat(sent.giverUserId()).isEqualTo(GIVER_ID);
        assertThat(sent.receiverUserId()).isEqualTo(RECEIVER_ID);
        assertThat(sent.revivalEventId()).isEqualTo(REVIVAL_EVENT_ID);

        ArgumentCaptor<PersonalPointsLedger> ledgerCap =
                ArgumentCaptor.forClass(PersonalPointsLedger.class);
        verify(personalLedger).save(ledgerCap.capture());
        PersonalPointsLedger saved = ledgerCap.getValue();
        assertThat(saved.getUserId()).isEqualTo(GIVER_ID);
        assertThat(saved.getDelta()).isEqualTo((short) -5);
        assertThat(saved.getReason()).isEqualTo(LedgerReason.FRIEND_GIFT_SPEND);
        assertThat(saved.getRevivalEventId()).isEqualTo(REVIVAL_EVENT_ID);
    }

    @Test
    @DisplayName("repeat donor — prior FRIEND_GIFT exists → isFirstEverFriendGiftSend=false")
    void reviveFriend_happy_repeatDonor() {
        primeHappyPath(/* balance = */ 10);
        when(revivalEvents.existsFriendGiftSendByGiver(GIVER_ID, REVIVAL_EVENT_ID))
                .thenReturn(true);
        FriendGiftRevivalDto dto = service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null);
        assertThat(dto.isFirstEverFriendGiftSend()).isFalse();
    }

    @Test
    @DisplayName("self-target → BadRequestException, no advisory lock")
    void reviveFriend_selfTarget_throwsBadRequest() {
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, GIVER_ID, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("자기 자신");
        verify(entityManager, never()).createNativeQuery(any(String.class));
    }

    @Test
    @DisplayName("target not a room member → NotFoundException")
    void reviveFriend_targetNotMember_throwsNotFound() {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, RECEIVER_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("giver not a room member → ForbiddenException")
    void reviveFriend_giverNotMember_throwsForbidden() {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, GIVER_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("target status ACTIVE → NotEliminatedException, no advisory lock")
    void reviveFriend_targetActive_throwsNotEliminated() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.ACTIVE, null);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(NotEliminatedException.class);
        verify(entityManager, never()).createNativeQuery(any(String.class));
    }

    @Test
    @DisplayName("RED with eliminatedAt=null → NotEliminatedException")
    void reviveFriend_redNullEliminatedAt_throwsNotEliminated() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, null);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(NotEliminatedException.class);
    }

    @Test
    @DisplayName("giver SPECTATOR → SpectatorWriteForbiddenException")
    void reviveFriend_giverSpectator_throwsSpectatorWrite() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        SurvivalState giverState = stateWithStatus(giver, SurvivalStatus.SPECTATOR, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, GIVER_ID))
                .thenReturn(Optional.of(giverState));
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(SpectatorWriteForbiddenException.class);
    }

    @Test
    @DisplayName("no friendship row → NotFriendsForGiftException")
    void reviveFriend_noFriendship_throwsNotFriends() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(friendships.findBetween(giver, receiver)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(NotFriendsForGiftException.class);
    }

    @Test
    @DisplayName("friendship PENDING (not ACCEPTED) → NotFriendsForGiftException")
    void reviveFriend_friendshipPending_throwsNotFriends() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(friendships.findBetween(giver, receiver))
                .thenReturn(Optional.of(makeFriendship(FriendshipStatus.PENDING)));
        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(NotFriendsForGiftException.class);
    }

    @Test
    @DisplayName("balance 4 → InsufficientGiftPointsException, no INSERT")
    void reviveFriend_balance4_throwsInsufficient() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(personalLedger.sumDeltaByUserIdAndRoomId(GIVER_ID, ROOM_ID)).thenReturn(4);

        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(InsufficientGiftPointsException.class);

        verify(revivalEvents, never()).insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("balance exactly 5 (boundary) → success")
    void reviveFriend_balanceExactly5_boundary() {
        primeHappyPath(/* balance = */ 5);
        FriendGiftRevivalDto dto = service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null);
        assertThat(dto.pointsSpent()).isEqualTo(5);
    }

    @Test
    @DisplayName("race loser — INSERT returns 0 → AlreadyRevivedException, no ledger/pool/events")
    void reviveFriend_insertReturnsZero_throwsAlreadyRevived() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(personalLedger.sumDeltaByUserIdAndRoomId(GIVER_ID, ROOM_ID)).thenReturn(5);
        when(roomPointPool.selectForUpdate(ROOM_ID))
                .thenReturn(Optional.of(new RoomPointPool(ROOM_ID, 0)));
        when(revivalEvents.insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(AlreadyRevivedException.class);

        verify(personalLedger, never()).save(any());
        verify(roomPointPool, never()).incrementTotal(anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("DataIntegrityViolation naming ux_revival_events_one_per_elimination → AlreadyRevivedException")
    void reviveFriend_dveDedup_translatesToAlreadyRevived() {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(personalLedger.sumDeltaByUserIdAndRoomId(GIVER_ID, ROOM_ID)).thenReturn(5);
        when(roomPointPool.selectForUpdate(ROOM_ID))
                .thenReturn(Optional.of(new RoomPointPool(ROOM_ID, 0)));
        when(revivalEvents.insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any()))
                .thenThrow(new DataIntegrityViolationException(
                        "constraint ux_revival_events_one_per_elimination",
                        new RuntimeException("ux_revival_events_one_per_elimination")));

        assertThatThrownBy(() -> service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null))
                .isInstanceOf(AlreadyRevivedException.class);
    }

    // ----- helpers -----

    private void primeHappyPath(int balance) {
        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(personalLedger.sumDeltaByUserIdAndRoomId(GIVER_ID, ROOM_ID)).thenReturn(balance);
        when(roomPointPool.selectForUpdate(ROOM_ID))
                .thenReturn(Optional.of(new RoomPointPool(ROOM_ID, 0)));
        when(revivalEvents.insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any()))
                .thenReturn(1);
        when(revivalEvents.findByRoomIdAndUserIdAndEliminatedAt(ROOM_ID, RECEIVER_ID, ELIMINATED_AT))
                .thenReturn(Optional.of(revivalEvent(REVIVAL_EVENT_ID)));
        when(roomPointPool.incrementTotal(ROOM_ID, 5)).thenReturn(1);
    }

    private SurvivalState stateWithStatus(User user, SurvivalStatus status, Instant eliminatedAt) {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        setField(s, "status", status);
        setField(s, "eliminatedAt", eliminatedAt);
        return s;
    }

    private static Friendship makeFriendship(FriendshipStatus status) {
        // Friendship's no-arg constructor is protected (JPA only). Use the
        // public 2-arg constructor with the dummy User instances from the
        // test fixture, then flip status via reflection — same pattern the
        // existing KudosServiceTest uses for cross-package entity setup.
        User a = new User("a@example.com", "A", "h", AuthProvider.EMAIL);
        User b = new User("b@example.com", "B", "h", AuthProvider.EMAIL);
        Friendship f = new Friendship(a, b);
        setField(f, "status", status);
        return f;
    }

    private static RevivalEvent revivalEvent(long id) {
        RevivalEvent ev = new RevivalEvent(
                ROOM_ID, RECEIVER_ID, GIVER_ID, RevivalSource.FRIEND_GIFT, "PUSH_INITIATED",
                (short) 5, 5, ELIMINATED_AT, NOW);
        setField(ev, "id", id);
        return ev;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner);
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
