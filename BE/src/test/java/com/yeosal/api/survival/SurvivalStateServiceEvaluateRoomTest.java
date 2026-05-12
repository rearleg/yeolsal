package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.notification.NotificationLogRepository;
import com.yeosal.api.revival.LedgerReason;
import com.yeosal.api.revival.PersonalPointsLedger;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Algorithm unit tests for {@link SurvivalStateService#evaluateRoom}.
 * Covers AC2–AC9 + the AC8 idempotency contract per Story 1.2 BE-7.1.
 *
 * <p>The grace guard is exercised through the real
 * {@link SurvivalStateService#inGraceWindow(SurvivalState, Instant)} — never
 * mocked, per AC6 contract.
 */
@ExtendWith(MockitoExtension.class)
class SurvivalStateServiceEvaluateRoomTest {

    private static final Instant NOW = Instant.parse("2026-05-11T03:14:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    // 2026-05-08 is a Friday — exercises the weekday compliance path.
    private static final LocalDate PRIOR = LocalDate.of(2026, 5, 8);
    private static final String MONTH_KEY = "2026-05";

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
    private User owner;
    private User alice;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new SurvivalStateService(
                survivalRepo, notificationLogs, users, streakFreezes, personalLedger,
                dailyEntries, roomMembers, rooms, ruleVersions, eventPublisher, CLOCK);
        owner = makeUser(1L, "owner@example.com", "Owner");
        alice = makeUser(7L, "alice@example.com", "Alice");
        room = makeRoom(42L, "방", owner);
    }

    @Test
    @DisplayName("compliant member → +2 SURVIVAL ledger row + dedup row, no state change, no event")
    void evaluateRoom_compliantMember_writesSurvivalPointsAndDedupRow() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR))
                .thenReturn(List.of(new DailyEntry(alice, PRIOR, "goal")));
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(1);

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.compliant()).isEqualTo(1);
        assertThat(result.frozen()).isZero();
        assertThat(result.toYellow()).isZero();
        assertThat(result.toRed()).isZero();
        assertThat(result.skipped()).isZero();

        ArgumentCaptor<PersonalPointsLedger> ledgerCap =
                ArgumentCaptor.forClass(PersonalPointsLedger.class);
        verify(personalLedger).save(ledgerCap.capture());
        PersonalPointsLedger row = ledgerCap.getValue();
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getRoomId()).isEqualTo(42L);
        assertThat(row.getDelta()).isEqualTo((short) 2);
        assertThat(row.getReason()).isEqualTo(LedgerReason.SURVIVAL);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);

        verify(streakFreezes, never())
                .insertIfAbsent(anyLong(), anyLong(), any(LocalDate.class), anyString());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("miss + freeze unused this month → freeze written, no state change, no event")
    void evaluateRoom_missWithoutFreezeUsed_consumesFreezeAndKeepsStatus() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR)).thenReturn(List.of());
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(1);
        when(streakFreezes.insertIfAbsent(7L, 42L, PRIOR, MONTH_KEY)).thenReturn(1);

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.frozen()).isEqualTo(1);
        assertThat(result.compliant()).isZero();
        assertThat(result.toYellow()).isZero();
        assertThat(result.toRed()).isZero();
        verify(personalLedger, never()).save(any());
        verify(survivalRepo, never()).findByRoomIdAndUserId(anyLong(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("miss + freeze already used + ACTIVE → ACTIVE→YELLOW transition + event")
    void evaluateRoom_missWithFreezeAlreadyUsed_activeMember_transitionsToYellow() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR)).thenReturn(List.of());
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(1);
        when(streakFreezes.insertIfAbsent(7L, 42L, PRIOR, MONTH_KEY)).thenReturn(0);
        SurvivalState state = newSurvivalState(SurvivalStatus.ACTIVE,
                NOW.minus(Duration.ofDays(30)),  // lastStateChangeAt irrelevant for ACTIVE
                NOW.minus(Duration.ofDays(1)));  // out of grace
        when(survivalRepo.findByRoomIdAndUserId(42L, 7L)).thenReturn(Optional.of(state));

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.toYellow()).isEqualTo(1);
        assertThat(state.getStatus()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(state.getLastStateChangeAt()).isEqualTo(NOW);
        assertThat(state.getEliminatedAt()).isNull();

        ArgumentCaptor<SurvivalStateTransitionEvent> cap =
                ArgumentCaptor.forClass(SurvivalStateTransitionEvent.class);
        verify(eventPublisher, times(1)).publishEvent(cap.capture());
        SurvivalStateTransitionEvent event = cap.getValue();
        assertThat(event.fromStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(event.toStatus()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.broadVisibilityAt()).isNull();
        assertThat(event.ownerUserId()).isEqualTo(1L);
        assertThat(event.roomId()).isEqualTo(42L);
        assertThat(event.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("miss + freeze already used + YELLOW in grace → clamped to YELLOW (AC6 hard guard)")
    void evaluateRoom_missWithFreezeAlreadyUsed_yellowMemberInGrace_clampsToYellow() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR)).thenReturn(List.of());
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(1);
        when(streakFreezes.insertIfAbsent(7L, 42L, PRIOR, MONTH_KEY)).thenReturn(0);
        SurvivalState state = newSurvivalState(SurvivalStatus.YELLOW,
                NOW.minus(Duration.ofDays(3)),   // within 7d rolling window
                NOW.plus(Duration.ofDays(1)));   // IN grace
        when(survivalRepo.findByRoomIdAndUserId(42L, 7L)).thenReturn(Optional.of(state));

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.toRed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(state.getStatus()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(state.getEliminatedAt()).isNull();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("miss + freeze used + YELLOW out of grace, within 7d → YELLOW→RED with broadVisibilityAt=now+24h")
    void evaluateRoom_missWithFreezeAlreadyUsed_yellowMemberOutsideGrace_within7d_transitionsToRed() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR)).thenReturn(List.of());
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(1);
        when(streakFreezes.insertIfAbsent(7L, 42L, PRIOR, MONTH_KEY)).thenReturn(0);
        SurvivalState state = newSurvivalState(SurvivalStatus.YELLOW,
                NOW.minus(Duration.ofDays(3)),    // within 7d rolling window
                NOW.minus(Duration.ofDays(1)));   // OUT of grace
        when(survivalRepo.findByRoomIdAndUserId(42L, 7L)).thenReturn(Optional.of(state));

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.toRed()).isEqualTo(1);
        assertThat(state.getStatus()).isEqualTo(SurvivalStatus.RED);
        assertThat(state.getLastStateChangeAt()).isEqualTo(NOW);
        assertThat(state.getEliminatedAt()).isEqualTo(NOW);
        assertThat(state.getBroadVisibilityAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));

        ArgumentCaptor<SurvivalStateTransitionEvent> cap =
                ArgumentCaptor.forClass(SurvivalStateTransitionEvent.class);
        verify(eventPublisher, times(1)).publishEvent(cap.capture());
        SurvivalStateTransitionEvent event = cap.getValue();
        assertThat(event.fromStatus()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(event.toStatus()).isEqualTo(SurvivalStatus.RED);
        assertThat(event.broadVisibilityAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("miss + freeze used + YELLOW outside 7d rolling window → keeps YELLOW")
    void evaluateRoom_missWithFreezeAlreadyUsed_yellowMemberOutsideRollingWindow_keepsYellow() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR)).thenReturn(List.of());
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(1);
        when(streakFreezes.insertIfAbsent(7L, 42L, PRIOR, MONTH_KEY)).thenReturn(0);
        SurvivalState state = newSurvivalState(SurvivalStatus.YELLOW,
                NOW.minus(Duration.ofDays(10)),   // OUTSIDE 7d rolling window
                NOW.minus(Duration.ofDays(1)));   // out of grace
        when(survivalRepo.findByRoomIdAndUserId(42L, 7L)).thenReturn(Optional.of(state));

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.toRed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(state.getStatus()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(state.getEliminatedAt()).isNull();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("retry for same date → dedup gate skips, zero additional ledger/state writes (AC8)")
    void evaluateRoom_retryForSameDate_writesZeroAdditionalRows() {
        stubRule(true);
        stubRoomMembers(alice);
        when(dailyEntries.findByUserInAndDate(List.of(alice), PRIOR))
                .thenReturn(List.of(new DailyEntry(alice, PRIOR, "goal")));
        when(notificationLogs.insertIfAbsent(7L, "SURVIVAL_STATE", PRIOR + ":42:7"))
                .thenReturn(0);  // already processed by an earlier run

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, PRIOR);

        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.compliant()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(personalLedger, never()).save(any());
        verify(streakFreezes, never())
                .insertIfAbsent(anyLong(), anyLong(), any(LocalDate.class), anyString());
        verify(survivalRepo, never()).findByRoomIdAndUserId(anyLong(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("weekend skip → returns evaluated=0, no dedup row, no ledger, no events (AC2)")
    void evaluateRoom_weekendSkip_returnsEvaluatedZero() {
        LocalDate saturday = LocalDate.of(2026, 5, 9);
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        eq(42L), eq("2026-05")))
                .thenReturn(Optional.of(makeRule(42L, "2026-05", false)));

        SurvivalStateService.EvaluationResult result = service.evaluateRoom(42L, saturday);

        assertThat(result.evaluated()).isZero();
        assertThat(result.compliant()).isZero();
        assertThat(result.frozen()).isZero();
        assertThat(result.toYellow()).isZero();
        assertThat(result.toRed()).isZero();
        assertThat(result.skipped()).isZero();
        verifyNoInteractions(notificationLogs);
        verifyNoInteractions(streakFreezes);
        verifyNoInteractions(personalLedger);
        verifyNoInteractions(eventPublisher);
    }

    // ----- helpers -----

    private void stubRule(boolean weekendInclude) {
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        eq(42L), eq(MONTH_KEY)))
                .thenReturn(Optional.of(makeRule(42L, MONTH_KEY, weekendInclude)));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
    }

    private void stubRoomMembers(User... members) {
        List<RoomMember> rms = java.util.Arrays.stream(members)
                .map(u -> new RoomMember(room, u, RoomRole.MEMBER))
                .toList();
        when(roomMembers.findByRoom(room)).thenReturn(rms);
    }

    private RoomRuleVersion makeRule(long roomId, String month, boolean weekendInclude) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("preset", "DAILY_UPDATE");
        node.put("weekendInclude", weekendInclude);
        return new RoomRuleVersion(roomId, month, node, 1L);
    }

    private SurvivalState newSurvivalState(
            SurvivalStatus status, Instant lastStateChangeAt, Instant graceEndsAt) {
        SurvivalState s = new SurvivalState(room, alice, graceEndsAt);
        setStateField(s, "status", status);
        setStateField(s, "lastStateChangeAt", lastStateChangeAt);
        return s;
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

    private static void setStateField(SurvivalState s, String name, Object value) {
        try {
            Field f = SurvivalState.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(s, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
