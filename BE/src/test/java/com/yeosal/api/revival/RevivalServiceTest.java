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

import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.friend.FriendshipRepository;
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

/**
 * Mockito unit tests for {@link RevivalService} (Story 3.1 AC11).
 *
 * <p>Covers the eight cases enumerated in the story:
 * <ol>
 *   <li>FREE_TICKET happy path — returns DTO, calls each repo in order,
 *       publishes both events.</li>
 *   <li>FREE_TICKET already used → {@link FreeTicketAlreadyUsedException}.</li>
 *   <li>Status ACTIVE on the pre-lock read → {@link AlreadyRevivedException}.</li>
 *   <li>Status ACTIVE after the post-lock refresh → {@link AlreadyRevivedException}.</li>
 *   <li>PERSONAL_POINTS happy path with balance ≥ 3 → debits ledger -3,
 *       increments pool +3.</li>
 *   <li>PERSONAL_POINTS balance 2 → {@link InsufficientPointsException}.</li>
 *   <li>PERSONAL_POINTS balance exactly 3 → succeeds (boundary).</li>
 *   <li>survival_state row missing → {@link NotFoundException}.</li>
 *   <li>RED status with {@code eliminated_at = null} → {@link NotEliminatedException}.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevivalServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-16T03:14:15Z");
    private static final Instant ELIMINATED_AT = Instant.parse("2026-05-15T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private static final long ROOM_ID = 42L;
    private static final long USER_ID = 7L;
    private static final long OWNER_ID = 1L;
    private static final long REVIVAL_EVENT_ID = 9001L;

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
    private User user;
    private User owner;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new RevivalService(
                survivalStates, rooms, users, revivalEvents, personalLedger,
                roomPointPool, roomPointPoolService, friendships, roomMembers,
                eventPublisher, entityManager, CLOCK);

        user = makeUser(USER_ID, "user@example.com", "User");
        owner = makeUser(OWNER_ID, "owner@example.com", "Owner");
        room = makeRoom(ROOM_ID, "Room", owner);

        // Default advisory-lock chain — tests override individual stubs as needed.
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.setParameter(any(String.class), any())).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.getSingleResult()).thenReturn(1L);

        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        // Default: user has already consumed their lifetime FREE_TICKET so
        // the PERSONAL_POINTS branch's AC3 lifetime-ordering guard passes
        // by default. The dedicated "PERSONAL_POINTS before ticket used"
        // test below overrides this stub.
        setField(user, "freeRevivalTicketUsed", true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("FREE_TICKET happy — RED + ticket unused → returns DTO, pool +5, publishes both events")
    void reviveSelf_freeTicket_happy() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        when(users.markFreeTicketUsed(USER_ID)).thenReturn(1);
        when(roomPointPool.selectForUpdate(ROOM_ID)).thenReturn(Optional.of(poolWithTotal(0)));
        when(revivalEvents.insertOnConflictDoNothing(
                eq(ROOM_ID), eq(USER_ID), eq(null), eq("FREE_TICKET"), eq(null),
                anyShort(), anyInt(), eq(ELIMINATED_AT), eq(NOW)))
                .thenReturn(1);
        when(revivalEvents.findByRoomIdAndUserIdAndEliminatedAt(ROOM_ID, USER_ID, ELIMINATED_AT))
                .thenReturn(Optional.of(revivalEventOf(REVIVAL_EVENT_ID)));
        when(roomPointPool.incrementTotal(ROOM_ID, (int) RevivalService.FREE_TICKET_POOL_DELTA))
                .thenReturn(1);

        RevivalEventDto dto = service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.FREE_TICKET);

        assertThat(dto.revivalEventId()).isEqualTo(REVIVAL_EVENT_ID);
        assertThat(dto.source()).isEqualTo("FREE_TICKET");
        assertThat(dto.pointsSpent()).isZero();
        assertThat(dto.roomPointPoolAfter())
                .isEqualTo((int) RevivalService.FREE_TICKET_POOL_DELTA);
        assertThat(dto.occurredAt()).isEqualTo(NOW);

        verify(users).markFreeTicketUsed(USER_ID);
        verify(personalLedger, never()).save(any());

        // Story 4.1 — RevivalService now publishes only the survival
        // transition event; PointPoolChangeEvent emission moved into
        // RoomPointPoolService.applyDelta (covered by RoomPointPoolServiceTest).
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(events.capture());
        assertThat(events.getAllValues().get(0)).isInstanceOf(SurvivalStateTransitionEvent.class);
        SurvivalStateTransitionEvent transition =
                (SurvivalStateTransitionEvent) events.getAllValues().get(0);
        assertThat(transition.toStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(transition.broadVisibilityAt()).isNull();

        // Pool mutation is delegated to the chokepoint service.
        verify(roomPointPoolService).applyDelta(
                ROOM_ID, (int) RevivalService.FREE_TICKET_POOL_DELTA,
                REVIVAL_EVENT_ID, NOW);
    }

    @Test
    @DisplayName("FREE_TICKET — ticket already consumed → FreeTicketAlreadyUsedException, no INSERT")
    void reviveSelf_freeTicket_alreadyUsed() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        when(users.markFreeTicketUsed(USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.FREE_TICKET))
                .isInstanceOf(FreeTicketAlreadyUsedException.class);

        verify(revivalEvents, never()).insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("status ACTIVE on the pre-lock read → AlreadyRevivedException, no advisory lock acquired")
    void reviveSelf_preLockActive_throwsAlreadyRevived() {
        SurvivalState state = activeState();
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.FREE_TICKET))
                .isInstanceOf(AlreadyRevivedException.class);
        verify(entityManager, never()).createNativeQuery(any(String.class));
    }

    @Test
    @DisplayName("status ACTIVE after the post-lock refresh (winner just committed) → AlreadyRevivedException")
    void reviveSelf_postLockActive_throwsAlreadyRevived() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        // refresh() flips status → ACTIVE to simulate the winner committing
        // between our pre-lock read and our post-lock re-read.
        org.mockito.Mockito.doAnswer(invocation -> {
            setField(state, "status", SurvivalStatus.ACTIVE);
            return null;
        }).when(entityManager).refresh(state);

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.FREE_TICKET))
                .isInstanceOf(AlreadyRevivedException.class);
        verify(users, never()).markFreeTicketUsed(anyLong());
    }

    @Test
    @DisplayName("PERSONAL_POINTS happy — balance ≥ 3 → debits ledger -3, pool +3, returns DTO with pointsSpent=3")
    void reviveSelf_personalPoints_happy() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        when(personalLedger.sumDeltaByUserIdAndRoomId(USER_ID, ROOM_ID)).thenReturn(6);
        when(roomPointPool.selectForUpdate(ROOM_ID)).thenReturn(Optional.of(poolWithTotal(10)));
        when(revivalEvents.insertOnConflictDoNothing(
                eq(ROOM_ID), eq(USER_ID), eq(null), eq("PERSONAL_POINTS"), eq(null),
                anyShort(), anyInt(), eq(ELIMINATED_AT), eq(NOW)))
                .thenReturn(1);
        when(revivalEvents.findByRoomIdAndUserIdAndEliminatedAt(ROOM_ID, USER_ID, ELIMINATED_AT))
                .thenReturn(Optional.of(revivalEventOf(REVIVAL_EVENT_ID)));
        when(roomPointPool.incrementTotal(ROOM_ID, (int) RevivalService.PERSONAL_POINTS_POOL_DELTA))
                .thenReturn(1);

        RevivalEventDto dto = service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.PERSONAL_POINTS);

        assertThat(dto.source()).isEqualTo("PERSONAL_POINTS");
        assertThat(dto.pointsSpent()).isEqualTo((int) RevivalService.PERSONAL_POINTS_COST);
        assertThat(dto.roomPointPoolAfter())
                .isEqualTo(10 + RevivalService.PERSONAL_POINTS_POOL_DELTA);

        ArgumentCaptor<PersonalPointsLedger> ledgerCap =
                ArgumentCaptor.forClass(PersonalPointsLedger.class);
        verify(personalLedger).save(ledgerCap.capture());
        PersonalPointsLedger saved = ledgerCap.getValue();
        assertThat(saved.getDelta()).isEqualTo((short) -RevivalService.PERSONAL_POINTS_COST);
        assertThat(saved.getReason()).isEqualTo(LedgerReason.REVIVAL_SPEND);
        assertThat(saved.getRevivalEventId()).isEqualTo(REVIVAL_EVENT_ID);

        verify(users, never()).markFreeTicketUsed(anyLong());
    }

    @Test
    @DisplayName("PERSONAL_POINTS — ticket NOT yet consumed → BadRequestException (AC3 lifetime ordering)")
    void reviveSelf_personalPoints_beforeTicketUsed_throwsBadRequest() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        setField(user, "freeRevivalTicketUsed", false);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.PERSONAL_POINTS))
                .isInstanceOf(com.yeosal.api.common.BadRequestException.class)
                .hasMessageContaining("무료 회생권");

        verify(personalLedger, never()).save(any(PersonalPointsLedger.class));
        verify(revivalEvents, never()).insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("PERSONAL_POINTS — balance 2 → InsufficientPointsException, no INSERT or pool lock")
    void reviveSelf_personalPoints_balance2() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        when(personalLedger.sumDeltaByUserIdAndRoomId(USER_ID, ROOM_ID)).thenReturn(2);

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.PERSONAL_POINTS))
                .isInstanceOf(InsufficientPointsException.class);

        verify(roomPointPool, never()).selectForUpdate(anyLong());
        verify(revivalEvents, never()).insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("PERSONAL_POINTS — balance exactly 3 → succeeds (boundary)")
    void reviveSelf_personalPoints_balanceExactly3_boundary() {
        SurvivalState state = redStateAt(ELIMINATED_AT);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));
        when(personalLedger.sumDeltaByUserIdAndRoomId(USER_ID, ROOM_ID))
                .thenReturn((int) RevivalService.PERSONAL_POINTS_COST);
        when(roomPointPool.selectForUpdate(ROOM_ID)).thenReturn(Optional.of(poolWithTotal(0)));
        when(revivalEvents.insertOnConflictDoNothing(
                anyLong(), anyLong(), any(), any(), any(), anyShort(), anyInt(), any(), any()))
                .thenReturn(1);
        when(revivalEvents.findByRoomIdAndUserIdAndEliminatedAt(ROOM_ID, USER_ID, ELIMINATED_AT))
                .thenReturn(Optional.of(revivalEventOf(REVIVAL_EVENT_ID)));
        when(roomPointPool.incrementTotal(ROOM_ID, (int) RevivalService.PERSONAL_POINTS_POOL_DELTA))
                .thenReturn(1);

        RevivalEventDto dto = service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.PERSONAL_POINTS);

        assertThat(dto.pointsSpent()).isEqualTo((int) RevivalService.PERSONAL_POINTS_COST);
        verify(personalLedger).save(any(PersonalPointsLedger.class));
    }

    @Test
    @DisplayName("survival_state row missing → NotFoundException")
    void reviveSelf_missingSurvivalState_throwsNotFound() {
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.FREE_TICKET))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("RED with eliminated_at null → NotEliminatedException, no advisory lock")
    void reviveSelf_redWithNullEliminatedAt_throwsNotEliminated() {
        SurvivalState state = redStateAt(null);
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(Optional.of(state));

        assertThatThrownBy(() -> service.reviveSelf(ROOM_ID, USER_ID, RevivalSource.FREE_TICKET))
                .isInstanceOf(NotEliminatedException.class);
        verify(entityManager, never()).createNativeQuery(any(String.class));
    }

    // ----- helpers -----

    private SurvivalState redStateAt(Instant eliminatedAt) {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        setField(s, "status", SurvivalStatus.RED);
        setField(s, "eliminatedAt", eliminatedAt);
        return s;
    }

    private SurvivalState activeState() {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        setField(s, "status", SurvivalStatus.ACTIVE);
        return s;
    }

    private static RoomPointPool poolWithTotal(int total) {
        return new RoomPointPool(ROOM_ID, total);
    }

    private static RevivalEvent revivalEventOf(long id) {
        RevivalEvent ev = new RevivalEvent(
                ROOM_ID, USER_ID, null, RevivalSource.FREE_TICKET, null,
                (short) 0, 0, ELIMINATED_AT, NOW);
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
