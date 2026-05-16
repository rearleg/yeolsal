package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomServiceEvaluationTest {

    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomInviteRepository roomInvites;
    @Mock private GroupMemberMinimumRepository minimums;
    @Mock private GroupWarningRepository warnings;
    @Mock private UserRepository users;
    @Mock private DailyService dailyService;
    @Mock private DailyEntryRepository dailyEntries;
    @Mock private ChatService chatService;
    @Mock private InviteCodeGenerator codeGenerator;
    @Mock private com.yeosal.api.realtime.RealtimePublisher realtime;
    @Mock private SurvivalStateService survivalState;
    @Mock private com.yeosal.api.survival.SurvivalStateRepository survivalStates;
    @Mock private com.yeosal.api.survival.RecordVisibilityPrefRepository visibilityPrefs;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-01T00:10:00Z"), ZoneId.of("UTC"));

    private RoomService service;
    private User alice;
    private User bob;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new RoomService(
                rooms,
                roomMembers,
                roomInvites,
                minimums,
                warnings,
                users,
                dailyService,
                dailyEntries,
                chatService,
                codeGenerator,
                clock,
                realtime,
                survivalState,
                survivalStates,
                visibilityPrefs);
        alice = makeUser(1L, "alice@example.com", "Alice");
        bob = makeUser(2L, "bob@example.com", "Bob");
        room = makeRoom(42L, "기본 방", alice);
    }

    @Test
    @DisplayName("evaluateRoom: missing room throws NotFoundException")
    void missingRoomThrows() {
        when(rooms.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateRoom(99L, YearMonth.of(2026, 4)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("evaluateRoom: completed >= required → no warning, no audit")
    void onTrackMembersAreLeftAlone() {
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, 2L, (short) 15);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(bobMin));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        when(dailyService.monthlyCompletedCount(bob, "2026-04")).thenReturn(20);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.evaluatedMembers()).isEqualTo(1);
        assertThat(result.newWarnings()).isEqualTo(0);
        assertThat(result.autoLefts()).isEqualTo(0);
        assertThat(bobMin.getWarningCount()).isEqualTo((short) 0);
        verifyNoInteractions(warnings);
        verify(roomMembers, never()).deleteByRoomAndUser(any(), any());
        verify(chatService, never()).publishSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("evaluateRoom: first miss writes audit, bumps warning to 1, no auto-leave")
    void firstMissProducesWarningOnly() {
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, 2L, (short) 15);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(bobMin));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        when(dailyService.monthlyCompletedCount(bob, "2026-04")).thenReturn(10);
        when(warnings.insertIfAbsent(
                eq(42L),
                eq(2L),
                eq(LocalDate.of(2026, 4, 1)),
                eq((short) 10),
                eq((short) 15),
                eq((short) 1))).thenReturn(1);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.newWarnings()).isEqualTo(1);
        assertThat(result.autoLefts()).isEqualTo(0);
        assertThat(bobMin.getWarningCount()).isEqualTo((short) 1);
        verify(roomMembers, never()).deleteByRoomAndUser(any(), any());
        verify(chatService, never()).publishSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("evaluateRoom: re-fired cron writes 0 audit rows → no double-increment")
    void idempotentOnRefire() {
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, 2L, (short) 15);
        bobMin.setWarningCount((short) 1);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(bobMin));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        when(dailyService.monthlyCompletedCount(bob, "2026-04")).thenReturn(10);
        when(warnings.insertIfAbsent(
                anyLong(),
                anyLong(),
                any(LocalDate.class),
                anyShort(),
                anyShort(),
                anyShort())).thenReturn(0);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.newWarnings()).isEqualTo(0);
        assertThat(result.autoLefts()).isEqualTo(0);
        assertThat(bobMin.getWarningCount()).isEqualTo((short) 1);
        verify(roomMembers, never()).deleteByRoomAndUser(any(), any());
        verify(chatService, never()).publishSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("evaluateRoom: 2nd miss for non-owner triggers auto-leave + AUTO_LEAVE chat")
    void secondMissAutoLeavesNonOwner() {
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, 2L, (short) 10);
        bobMin.setWarningCount((short) 1);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(bobMin));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        when(dailyService.monthlyCompletedCount(bob, "2026-04")).thenReturn(5);
        when(warnings.insertIfAbsent(
                eq(42L),
                eq(2L),
                eq(LocalDate.of(2026, 4, 1)),
                eq((short) 5),
                eq((short) 10),
                eq((short) 2))).thenReturn(1);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.autoLefts()).isEqualTo(1);
        assertThat(bobMin.getWarningCount()).isEqualTo((short) 2);
        verify(roomMembers).deleteByRoomAndUser(room, bob);
        verify(chatService).publishSystem(
                eq(42L),
                eq(ChatMessageKind.AUTO_LEAVE),
                anyString(),
                anyString());
    }

    @Test
    @DisplayName("evaluateRoom: owner reaching 2 warnings keeps their seat (audit only)")
    void secondMissKeepsOwnerSeat() {
        GroupMemberMinimum aliceMin = new GroupMemberMinimum(42L, 1L, (short) 10);
        aliceMin.setWarningCount((short) 1);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(aliceMin));
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        when(dailyService.monthlyCompletedCount(alice, "2026-04")).thenReturn(2);
        when(warnings.insertIfAbsent(
                anyLong(),
                anyLong(),
                any(LocalDate.class),
                anyShort(),
                anyShort(),
                anyShort())).thenReturn(1);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.autoLefts()).isEqualTo(0);
        assertThat(aliceMin.getWarningCount()).isEqualTo((short) 2);
        verify(roomMembers, never()).deleteByRoomAndUser(any(), any());
        verify(chatService, never()).publishSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("evaluateRoom: minimum=31 in a 30-day month caps required at month length")
    void thirtyOneCapsToMonthLengthInApril() {
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, 2L, (short) 31);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(bobMin));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        when(dailyService.monthlyCompletedCount(bob, "2026-04")).thenReturn(30);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.newWarnings()).isEqualTo(0);
        verifyNoInteractions(warnings);
    }

    @Test
    @DisplayName("evaluateRoom: minimum=31 in a 31-day month requires every day")
    void thirtyOneRequiresEveryDayInMarch() {
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, 2L, (short) 31);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(bobMin));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        when(dailyService.monthlyCompletedCount(bob, "2026-03")).thenReturn(30);
        when(warnings.insertIfAbsent(
                eq(42L),
                eq(2L),
                eq(LocalDate.of(2026, 3, 1)),
                eq((short) 30),
                eq((short) 31),
                eq((short) 1))).thenReturn(1);

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 3));

        assertThat(result.newWarnings()).isEqualTo(1);
    }

    @Test
    @DisplayName("evaluateRoom: orphan minimum row (user not found) is skipped, not throwing")
    void orphanedMinimumRowSkipped() {
        GroupMemberMinimum stale = new GroupMemberMinimum(42L, 999L, (short) 10);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(minimums.findByRoomIdForEvaluation(42L)).thenReturn(List.of(stale));
        when(users.findById(999L)).thenReturn(Optional.empty());

        RoomService.EvaluationResult result = service.evaluateRoom(42L, YearMonth.of(2026, 4));

        assertThat(result.evaluatedMembers()).isEqualTo(1);
        assertThat(result.newWarnings()).isEqualTo(0);
        verifyNoInteractions(warnings);
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
}
