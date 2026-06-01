package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
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

/**
 * Story 3.3 BE-8 / AC3 — focused unit assertions for the
 * {@code sourceSubtype} passthrough on {@link RevivalService#reviveFriend}.
 *
 * <p>The broader 14-case behavioural matrix lives in
 * {@link FriendGiftServiceTest}. This file zooms in on the single
 * invariant Story 3.3 introduces: the 5th positional argument to
 * {@link RevivalEventRepository#insertOnConflictDoNothing} reflects the
 * caller's {@link RevivalSourceSubtype} verbatim, with {@code null}
 * defaulting to {@code PUSH_INITIATED} (backward compat for Story 3.2
 * callers).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevivalServiceSourceSubtypeTest {

    private static final Instant NOW = Instant.parse("2026-05-19T03:14:15Z");
    private static final Instant ELIMINATED_AT = Instant.parse("2026-05-18T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private static final long ROOM_ID = 42L;
    private static final long GIVER_ID = 7L;
    private static final long RECEIVER_ID = 11L;
    private static final long OWNER_ID = 1L;
    private static final long REVIVAL_EVENT_ID = 9101L;

    @Mock private SurvivalStateRepository survivalStates;
    @Mock private RoomRepository rooms;
    @Mock private UserRepository users;
    @Mock private RevivalEventRepository revivalEvents;
    @Mock private PersonalPointsLedgerRepository personalLedger;
    @Mock private RoomPointPoolRepository roomPointPool;
    @Mock private RoomPointPoolService roomPointPoolService;
    @Mock private FriendshipRepository friendships;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query advisoryLockQuery;

    private RevivalService service;
    private User giver;
    private User receiver;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new RevivalService(
                survivalStates, rooms, users, revivalEvents, personalLedger,
                roomPointPool, roomPointPoolService, friendships, roomMembers,
                eventPublisher, entityManager, CLOCK);

        giver = makeUser(GIVER_ID, "giver@example.com", "Giver");
        receiver = makeUser(RECEIVER_ID, "receiver@example.com", "Receiver");
        User owner = makeUser(OWNER_ID, "owner@example.com", "Owner");
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

        SurvivalState target = stateWithStatus(receiver, SurvivalStatus.RED, ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, RECEIVER_ID))
                .thenReturn(Optional.of(target));
        when(personalLedger.sumDeltaByUserIdAndRoomId(GIVER_ID, ROOM_ID)).thenReturn(5);
        when(roomPointPool.selectForUpdate(ROOM_ID))
                .thenReturn(Optional.of(new RoomPointPool(ROOM_ID, 0)));
        when(revivalEvents.insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any()))
                .thenReturn(1);
        when(revivalEvents.findByRoomIdAndUserIdAndEliminatedAt(ROOM_ID, RECEIVER_ID, ELIMINATED_AT))
                .thenReturn(Optional.of(revivalEvent(REVIVAL_EVENT_ID)));
        when(roomPointPool.incrementTotal(ROOM_ID, 5)).thenReturn(1);
        when(revivalEvents.existsFriendGiftSendByGiver(GIVER_ID, REVIVAL_EVENT_ID))
                .thenReturn(false);
    }

    @Test
    @DisplayName("sourceSubtype=WALLET_INITIATED → INSERT receives \"WALLET_INITIATED\"")
    void reviveFriend_walletInitiated_persistsString() {
        service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, RevivalSourceSubtype.WALLET_INITIATED);
        assertThat(capturedSubtype()).isEqualTo("WALLET_INITIATED");
    }

    @Test
    @DisplayName("sourceSubtype=PUSH_INITIATED → INSERT receives \"PUSH_INITIATED\"")
    void reviveFriend_pushInitiated_persistsString() {
        service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, RevivalSourceSubtype.PUSH_INITIATED);
        assertThat(capturedSubtype()).isEqualTo("PUSH_INITIATED");
    }

    @Test
    @DisplayName("sourceSubtype=null → INSERT defaults to \"PUSH_INITIATED\" (Story 3.2 backward compat)")
    void reviveFriend_nullSubtype_defaultsToPush() {
        service.reviveFriend(ROOM_ID, giver, RECEIVER_ID, null);
        assertThat(capturedSubtype()).isEqualTo("PUSH_INITIATED");
    }

    // ----- helpers -----

    private String capturedSubtype() {
        ArgumentCaptor<String> subtypeCap = ArgumentCaptor.forClass(String.class);
        // 9-arg method; positional index 4 (the 5th argument) is the subtype.
        verify(revivalEvents).insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), subtypeCap.capture(),
                anyShort(), anyInt(), any(), any());
        return subtypeCap.getValue();
    }

    private SurvivalState stateWithStatus(User user, SurvivalStatus status, Instant eliminatedAt) {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        setField(s, "status", status);
        setField(s, "eliminatedAt", eliminatedAt);
        return s;
    }

    private static Friendship makeFriendship(FriendshipStatus status) {
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
