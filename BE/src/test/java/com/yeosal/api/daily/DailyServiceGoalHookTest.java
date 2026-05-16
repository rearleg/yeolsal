package com.yeosal.api.daily;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.survival.RecordVisibilityPrefRepository;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the production bug "목표 작성 - 채팅에 안 남음": when the
 * actor's daily entry already exists (because today() / updateToday /
 * createTodo touched it earlier in the request flow), the old isNew check
 * silently skips the GOAL system-message publish even on the very first
 * goal text. The fix re-frames the gate as "first time this entry has a
 * non-blank goal", so re-saves don't re-fire but a real first goal does.
 */
class DailyServiceGoalHookTest {

    private final Instant now = Instant.parse("2026-05-03T01:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));
    private final LocalDate today = LocalDate.of(2026, 5, 3);

    private DailyEntryRepository entries;
    private TodoItemRepository todos;
    private ReflectionRepository reflections;
    private EntryDateResolver entryDateResolver;
    private GateRule gateRule;
    private RoomMemberRepository roomMembers;
    private NotificationService notifications;
    private ChatService chatService;

    private DailyService service;
    private User alice;
    private Room room;

    @BeforeEach
    void setUp() {
        entries = mock(DailyEntryRepository.class);
        todos = mock(TodoItemRepository.class);
        reflections = mock(ReflectionRepository.class);
        entryDateResolver = mock(EntryDateResolver.class);
        gateRule = mock(GateRule.class);
        roomMembers = mock(RoomMemberRepository.class);
        notifications = mock(NotificationService.class);
        chatService = mock(ChatService.class);

        service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications, chatService,
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));

        alice = makeUser(1L, "alice@example.com", "Alice");
        room = makeRoom(42L, "기본 방", alice);

        lenient().when(entryDateResolver.resolve(any(), any())).thenReturn(today);
        lenient().when(roomMembers.findRoomsByUser(alice)).thenReturn(List.of(room));
        lenient().when(roomMembers.findRoomMates(alice)).thenReturn(List.of());
    }

    @Test
    @DisplayName("entry-less first-goal write still publishes the GOAL chat row")
    void newEntryFirstGoalPublishes() {
        when(entries.findByUserAndDate(alice, today)).thenReturn(Optional.empty());
        when(entries.save(any(DailyEntry.class))).thenAnswer(inv -> {
            DailyEntry e = inv.getArgument(0);
            setId(e, 999L);
            return e;
        });

        service.createOrReplace(alice, new DailyController.DailyEntryCreate("오늘 목표", List.of()));

        verify(chatService, atLeastOnce()).publishSystem(
                eq(42L), eq(ChatMessageKind.GOAL), anyString(), anyString());
    }

    @Test
    @DisplayName("existing entry with a blank goal still fires the GOAL hook on the first real text")
    void existingEntryEmptyGoalStillPublishes() {
        // Today's entry already exists (e.g. created by an earlier todos
        // workflow) but the goal field is blank — the GOAL hook must still
        // fire on the next createOrReplace that supplies a non-blank goal.
        DailyEntry existing = new DailyEntry(alice, today, "");
        setId(existing, 7L);
        when(entries.findByUserAndDate(alice, today)).thenReturn(Optional.of(existing));
        when(entries.save(any(DailyEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createOrReplace(alice, new DailyController.DailyEntryCreate("새 목표", List.of()));

        verify(chatService, atLeastOnce()).publishSystem(
                eq(42L), eq(ChatMessageKind.GOAL), anyString(), anyString());
    }

    @Test
    @DisplayName("re-saving an entry whose goal is already filled in does NOT re-publish")
    void existingEntryFilledGoalIsIdempotent() {
        DailyEntry existing = new DailyEntry(alice, today, "기존 목표");
        setId(existing, 7L);
        when(entries.findByUserAndDate(alice, today)).thenReturn(Optional.of(existing));
        when(entries.save(any(DailyEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createOrReplace(alice, new DailyController.DailyEntryCreate("기존 목표", List.of()));

        verify(chatService, never()).publishSystem(
                anyLong(), eq(ChatMessageKind.GOAL), anyString(), anyString());
    }

    @Test
    @DisplayName("updateToday publishes the GOAL chat row on the first non-blank goal of the day")
    void updateTodayFiresGoalHookOnFirstGoal() {
        // The FE sends PATCH /daily-entries/today, which routes to
        // updateToday — not createOrReplace. updateToday must mirror the
        // same firstGoalToday gate or the chat row never lands.
        when(entries.findByUserAndDate(alice, today)).thenReturn(Optional.empty());
        when(entries.save(any(DailyEntry.class))).thenAnswer(inv -> {
            DailyEntry e = inv.getArgument(0);
            setId(e, 555L);
            return e;
        });

        service.updateToday(alice, new DailyController.DailyEntryUpdate("오늘 목표"));

        verify(chatService, atLeastOnce()).publishSystem(
                eq(42L), eq(ChatMessageKind.GOAL), anyString(), anyString());
    }

    @Test
    @DisplayName("updateToday is idempotent — same goal, no re-publish")
    void updateTodaySecondCallIsIdempotent() {
        DailyEntry existing = new DailyEntry(alice, today, "기존 목표");
        setId(existing, 7L);
        when(entries.findByUserAndDate(alice, today)).thenReturn(Optional.of(existing));
        when(entries.save(any(DailyEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateToday(alice, new DailyController.DailyEntryUpdate("새 목표"));

        verify(chatService, never()).publishSystem(
                anyLong(), eq(ChatMessageKind.GOAL), anyString(), anyString());
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
