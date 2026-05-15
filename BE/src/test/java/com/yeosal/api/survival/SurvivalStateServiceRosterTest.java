package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.notification.NotificationLogRepository;
import com.yeosal.api.revival.PersonalPointsLedgerRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Algorithm unit tests for {@link SurvivalStateService#roster(long, long)}.
 *
 * <p>Exercises the masking predicate in isolation — all five conjuncts from
 * AC3 (status=RED, broadVisibilityAt &gt; now exclusive, not-self, not-leader)
 * are covered along with the AC4 leader bypass, AC5 self-bypass, AC6 cooldown-
 * elapsed, AC7 non-RED pass-through, and the AC12 borderline
 * {@code now == broadVisibilityAt} case (exclusive boundary — actual RED
 * returned).
 *
 * <p>Wall-clock reads come from {@code Clock.fixed(...)} — the project rule is
 * "never call {@code Instant.now()} directly" (AC8 + project-context).
 */
@ExtendWith(MockitoExtension.class)
class SurvivalStateServiceRosterTest {

    private static final Instant NOW = Instant.parse("2026-05-12T03:14:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private static final long ROOM_ID = 42L;
    private static final long ALICE_ID = 1L;   // owner
    private static final long BOB_ID = 7L;     // member
    private static final long CAROL_ID = 9L;   // member
    private static final long DAVE_ID = 99L;   // outsider

    @Mock private SurvivalStateRepository survivalRepo;
    @Mock private NotificationLogRepository notificationLogs;
    @Mock private UserRepository users;
    @Mock private StreakFreezeRepository streakFreezes;
    @Mock private PersonalPointsLedgerRepository personalLedger;
    @Mock private DailyEntryRepository dailyEntries;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomRepository rooms;
    @Mock private RoomRuleVersionRepository ruleVersions;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SurvivalStateService service;
    private User alice;
    private User bob;
    private User carol;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new SurvivalStateService(
                survivalRepo, notificationLogs, users, streakFreezes, personalLedger,
                dailyEntries, roomMembers, rooms, ruleVersions, eventPublisher, CLOCK);
        alice = makeUser(ALICE_ID, "alice@example.com", "Alice");
        bob = makeUser(BOB_ID, "bob@example.com", "Bob");
        carol = makeUser(CAROL_ID, "carol@example.com", "Carol");
        room = makeRoom(ROOM_ID, "방", alice);
    }

    @Test
    @DisplayName("non-leader, non-self viewer → RED row masked as ACTIVE during cooldown (AC3)")
    void roster_nonLeaderNonSelf_sees_masked_red_during_cooldown() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(alice, bob, carol);
        SurvivalState carolRed = redState(carol,
                /* eliminatedAt */ NOW.minus(Duration.ofHours(10)),
                /* broadVisibilityAt */ NOW.plus(Duration.ofHours(14)));
        SurvivalState aliceActive = activeState(alice, NOW.minus(Duration.ofDays(1)));
        SurvivalState bobActive = activeState(bob, NOW.minus(Duration.ofDays(1)));
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID))
                .thenReturn(List.of(aliceActive, bobActive, carolRed));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(carolRow.eliminatedAt()).isNull();
        assertThat(carolRow.broadVisibilityAt()).isNull();
        assertThat(carolRow.lastStateChangeAt()).isNull();
    }

    @Test
    @DisplayName("non-leader, non-self viewer → RED row unmasked after cooldown elapsed (AC6)")
    void roster_nonLeaderNonSelf_sees_actual_red_after_cooldown_elapsed() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(alice, bob, carol);
        Instant eliminated = NOW.minus(Duration.ofHours(48));
        Instant broadVis = NOW.minus(Duration.ofHours(24));
        SurvivalState carolRed = redState(carol, eliminated, broadVis);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of(carolRed));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.RED);
        assertThat(carolRow.eliminatedAt()).isEqualTo(eliminated);
        assertThat(carolRow.broadVisibilityAt()).isEqualTo(broadVis);
    }

    @Test
    @DisplayName("leader → RED row unmasked during cooldown (AC4 leader bypass)")
    void roster_leader_sees_actual_red_during_cooldown() {
        stubRoom();
        stubMembership(ALICE_ID, true);
        stubMembers(alice, bob, carol);
        Instant eliminated = NOW.minus(Duration.ofHours(5));
        Instant broadVis = NOW.plus(Duration.ofHours(19));
        SurvivalState carolRed = redState(carol, eliminated, broadVis);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of(carolRed));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, ALICE_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.RED);
        assertThat(carolRow.eliminatedAt()).isEqualTo(eliminated);
        assertThat(carolRow.broadVisibilityAt()).isEqualTo(broadVis);
    }

    @Test
    @DisplayName("self → viewer always sees own actual RED during cooldown (AC5 self bypass)")
    void roster_self_sees_own_actual_red_during_cooldown() {
        stubRoom();
        stubMembership(CAROL_ID, true);
        stubMembers(alice, bob, carol);
        Instant eliminated = NOW.minus(Duration.ofHours(2));
        Instant broadVis = NOW.plus(Duration.ofHours(22));
        SurvivalState carolRed = redState(carol, eliminated, broadVis);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of(carolRed));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, CAROL_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.RED);
        assertThat(carolRow.eliminatedAt()).isEqualTo(eliminated);
        assertThat(carolRow.broadVisibilityAt()).isEqualTo(broadVis);
    }

    @Test
    @DisplayName("YELLOW row is never masked even for non-leader, non-self viewer (AC7)")
    void roster_yellow_never_masked() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(alice, bob, carol);
        Instant changedAt = NOW.minus(Duration.ofHours(3));
        SurvivalState carolYellow = stateOf(carol, SurvivalStatus.YELLOW,
                changedAt, /* eliminatedAt */ null, /* broadVisibilityAt */ null);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of(carolYellow));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(carolRow.lastStateChangeAt()).isEqualTo(changedAt);
        assertThat(carolRow.eliminatedAt()).isNull();
        assertThat(carolRow.broadVisibilityAt()).isNull();
    }

    @Test
    @DisplayName("SPECTATOR row is never masked (AC7)")
    void roster_spectator_never_masked() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(alice, bob, carol);
        Instant changedAt = NOW.minus(Duration.ofDays(2));
        SurvivalState carolSpectator = stateOf(carol, SurvivalStatus.SPECTATOR,
                changedAt, /* eliminatedAt */ NOW.minus(Duration.ofDays(3)),
                /* broadVisibilityAt */ NOW.minus(Duration.ofDays(2)));
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of(carolSpectator));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.SPECTATOR);
        assertThat(carolRow.lastStateChangeAt()).isEqualTo(changedAt);
    }

    @Test
    @DisplayName("empty room → empty list response (defensive)")
    void roster_emptyRoom_returnsEmpty() {
        stubRoom();
        stubMembership(BOB_ID, true);
        when(roomMembers.findByRoomFetchingUser(room)).thenReturn(List.of());
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of());

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("non-member viewer → ForbiddenException (AC2)")
    void roster_nonMember_throwsForbidden() {
        stubRoom();
        stubMembership(DAVE_ID, false);

        assertThatThrownBy(() -> service.roster(ROOM_ID, DAVE_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("방 멤버");
    }

    @Test
    @DisplayName("missing room → NotFoundException (AC2 ordering: 404 over 403)")
    void roster_missingRoom_throwsNotFound() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.roster(ROOM_ID, DAVE_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("방");
    }

    @Test
    @DisplayName("borderline now == broadVisibilityAt → actual RED returned (AC3 exclusive boundary)")
    void roster_borderlineExactlyAtBroadVisibilityAt_returnsActualRed() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(alice, bob, carol);
        Instant eliminated = NOW.minus(Duration.ofHours(24));
        Instant broadVis = NOW;
        SurvivalState carolRed = redState(carol, eliminated, broadVis);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID)).thenReturn(List.of(carolRed));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        SurvivalStateDto carolRow = findRow(result, CAROL_ID);
        assertThat(carolRow.status()).isEqualTo(SurvivalStatus.RED);
        assertThat(carolRow.eliminatedAt()).isEqualTo(eliminated);
        assertThat(carolRow.broadVisibilityAt()).isEqualTo(broadVis);
    }

    @Test
    @DisplayName("DTO order matches roomMembers.findByRoom iteration order (AC9)")
    void roster_orderMatchesMemberRepositoryIteration() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(carol, alice, bob);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID))
                .thenReturn(List.of(
                        activeState(alice, NOW.minus(Duration.ofDays(1))),
                        activeState(bob, NOW.minus(Duration.ofDays(1))),
                        activeState(carol, NOW.minus(Duration.ofDays(1)))));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        assertThat(result).extracting(SurvivalStateDto::userId)
                .containsExactly(CAROL_ID, ALICE_ID, BOB_ID);
    }

    @Test
    @DisplayName("member with no survival_state row → defensive ACTIVE-by-default")
    void roster_memberWithoutSurvivalRow_defaultsToActive() {
        stubRoom();
        stubMembership(BOB_ID, true);
        stubMembers(alice, bob, carol);
        when(survivalRepo.findByRoomIdFetchingUser(ROOM_ID))
                .thenReturn(List.of(activeState(alice, NOW.minus(Duration.ofDays(1)))));

        List<SurvivalStateDto> result = service.roster(ROOM_ID, BOB_ID);

        SurvivalStateDto bobRow = findRow(result, BOB_ID);
        assertThat(bobRow.status()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(bobRow.lastStateChangeAt()).isNull();
        assertThat(bobRow.eliminatedAt()).isNull();
        assertThat(bobRow.broadVisibilityAt()).isNull();
    }

    // ----- helpers -----

    private void stubRoom() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    private void stubMembership(long viewerUserId, boolean isMember) {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, viewerUserId)).thenReturn(isMember);
    }

    private void stubMembers(User... members) {
        List<RoomMember> rms = java.util.Arrays.stream(members)
                .map(u -> new RoomMember(room, u, RoomRole.MEMBER))
                .toList();
        when(roomMembers.findByRoomFetchingUser(room)).thenReturn(rms);
    }

    private SurvivalState activeState(User user, Instant lastChangeAt) {
        return stateOf(user, SurvivalStatus.ACTIVE, lastChangeAt, null, null);
    }

    private SurvivalState redState(User user, Instant eliminatedAt, Instant broadVisibilityAt) {
        return stateOf(user, SurvivalStatus.RED, eliminatedAt, eliminatedAt, broadVisibilityAt);
    }

    private SurvivalState stateOf(
            User user,
            SurvivalStatus status,
            Instant lastChangeAt,
            Instant eliminatedAt,
            Instant broadVisibilityAt) {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        setField(s, "status", status);
        setField(s, "lastStateChangeAt", lastChangeAt);
        setField(s, "eliminatedAt", eliminatedAt);
        setField(s, "broadVisibilityAt", broadVisibilityAt);
        return s;
    }

    private static SurvivalStateDto findRow(List<SurvivalStateDto> rows, long userId) {
        return rows.stream()
                .filter(r -> r.userId() == userId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("userId=" + userId + " not in response"));
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setId(u, id);
        return u;
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner);
        setId(r, id);
        return r;
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

    private static void setField(SurvivalState state, String name, Object value) {
        try {
            Field f = SurvivalState.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(state, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
