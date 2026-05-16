package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class DailyServiceReflectionsTest {

    private final Instant now = Instant.parse("2026-04-30T10:45:32Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));

    @Test
    @DisplayName("recentReflections returns ReflectionView records with lazy fields resolved inside transaction")
    void recentReflections_returnsViewRecord_avoidingLazyInit() {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        com.yeosal.api.room.RoomMemberRepository roomMembers =
                mock(com.yeosal.api.room.RoomMemberRepository.class);
        com.yeosal.api.notification.NotificationService notifications =
                mock(com.yeosal.api.notification.NotificationService.class);

        DailyService service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications,
                mock(com.yeosal.api.room.chat.ChatService.class),
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-29"), "오늘 목표");
        setId(entry, 100L);
        Reflection r1 = new Reflection(entry, "회고 본문 1");
        setId(r1, 11L);
        setField(r1, "submittedAt", now);

        when(reflections.findRecentByUser(eq(alice), any(Pageable.class)))
                .thenReturn(List.of(r1));

        List<DailyService.ReflectionView> result = service.recentReflections(alice, 10);

        assertThat(result).hasSize(1);
        DailyService.ReflectionView view = result.get(0);
        assertThat(view.date()).isEqualTo(LocalDate.parse("2026-04-29"));
        assertThat(view.body()).isEqualTo("회고 본문 1");
        assertThat(view.submittedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("recentReflections caps the limit between 1 and 100")
    void recentReflections_capsLimit() {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        com.yeosal.api.room.RoomMemberRepository roomMembers =
                mock(com.yeosal.api.room.RoomMemberRepository.class);
        com.yeosal.api.notification.NotificationService notifications =
                mock(com.yeosal.api.notification.NotificationService.class);

        DailyService service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications,
                mock(com.yeosal.api.room.chat.ChatService.class),
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));
        User alice = makeUser(1L, "alice@example.com", "Alice");

        when(reflections.findRecentByUser(eq(alice), any(Pageable.class))).thenReturn(List.of());

        service.recentReflections(alice, 0);
        service.recentReflections(alice, 9999);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(reflections, org.mockito.Mockito.times(2))
                .findRecentByUser(eq(alice), captor.capture());
        List<Pageable> pageables = captor.getAllValues();
        assertThat(pageables.get(0)).isEqualTo(PageRequest.of(0, 1));
        assertThat(pageables.get(1)).isEqualTo(PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("createReflection: pre-flight find sees existing reflection → BadRequestException")
    void createReflection_alreadySubmitted_throwsBadRequest() {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        com.yeosal.api.room.RoomMemberRepository roomMembers =
                mock(com.yeosal.api.room.RoomMemberRepository.class);
        com.yeosal.api.notification.NotificationService notifications =
                mock(com.yeosal.api.notification.NotificationService.class);

        DailyService service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications,
                mock(com.yeosal.api.room.chat.ChatService.class),
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(entry, 100L);
        Reflection existing = new Reflection(entry, "기존 회고");
        setId(existing, 11L);

        when(entries.findById(100L)).thenReturn(Optional.of(entry));
        when(reflections.findByDailyEntry(entry)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                service.createReflection(alice, new DailyController.ReflectionCreate(100L, "두 번째 본문")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("이미 회고를 제출했습니다.");

        verify(reflections, never()).saveAndFlush(any(Reflection.class));
    }

    @Test
    @DisplayName("createReflection: dup race on saveAndFlush is remapped to BadRequestException (no 500 leak)")
    void createReflection_dupRace_remapsToBadRequest() {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        com.yeosal.api.room.RoomMemberRepository roomMembers =
                mock(com.yeosal.api.room.RoomMemberRepository.class);
        com.yeosal.api.notification.NotificationService notifications =
                mock(com.yeosal.api.notification.NotificationService.class);

        DailyService service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications,
                mock(com.yeosal.api.room.chat.ChatService.class),
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(entry, 100L);

        when(entries.findById(100L)).thenReturn(Optional.of(entry));
        when(reflections.findByDailyEntry(entry)).thenReturn(Optional.empty());
        when(reflections.saveAndFlush(any(Reflection.class)))
                .thenThrow(new DataIntegrityViolationException("uq_reflections_daily_entry_id"));

        assertThatThrownBy(() ->
                service.createReflection(alice, new DailyController.ReflectionCreate(100L, "본문")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("이미 회고를 제출했습니다.");
    }

    @Test
    @DisplayName("createReflection: fans a REFLECTION system message to every room the actor belongs to")
    void createReflection_fansOutSystemMessage() {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        com.yeosal.api.room.RoomMemberRepository roomMembers =
                mock(com.yeosal.api.room.RoomMemberRepository.class);
        com.yeosal.api.notification.NotificationService notifications =
                mock(com.yeosal.api.notification.NotificationService.class);
        com.yeosal.api.room.chat.ChatService chatService =
                mock(com.yeosal.api.room.chat.ChatService.class);

        DailyService service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications, chatService,
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(entry, 100L);
        com.yeosal.api.room.Room roomA =
                new com.yeosal.api.room.Room("기록 모임 A", alice);
        setId(roomA, 42L);
        com.yeosal.api.room.Room roomB =
                new com.yeosal.api.room.Room("기록 모임 B", alice);
        setId(roomB, 43L);

        when(entries.findById(100L)).thenReturn(Optional.of(entry));
        when(reflections.findByDailyEntry(entry)).thenReturn(Optional.empty());
        Reflection saved = new Reflection(entry, "회고");
        setId(saved, 11L);
        when(reflections.saveAndFlush(any(Reflection.class))).thenReturn(saved);
        when(roomMembers.findRoomsByUser(alice)).thenReturn(java.util.List.of(roomA, roomB));

        service.createReflection(alice,
                new DailyController.ReflectionCreate(100L, "회고"));

        org.mockito.Mockito.verify(chatService).publishSystem(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(com.yeosal.api.room.chat.ChatMessageKind.REFLECTION),
                org.mockito.ArgumentMatchers.contains("Alice님"),
                org.mockito.ArgumentMatchers.contains("\"actorUserId\":1"));
        org.mockito.Mockito.verify(chatService).publishSystem(
                org.mockito.ArgumentMatchers.eq(43L),
                org.mockito.ArgumentMatchers.eq(com.yeosal.api.room.chat.ChatMessageKind.REFLECTION),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("createReflection: a single-room publish failure does not roll back the actor's reflection write")
    void createReflection_isolatesPerRoomPublishFailure() {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        com.yeosal.api.room.RoomMemberRepository roomMembers =
                mock(com.yeosal.api.room.RoomMemberRepository.class);
        com.yeosal.api.notification.NotificationService notifications =
                mock(com.yeosal.api.notification.NotificationService.class);
        com.yeosal.api.room.chat.ChatService chatService =
                mock(com.yeosal.api.room.chat.ChatService.class);

        DailyService service = new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications, chatService,
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(entry, 100L);
        com.yeosal.api.room.Room room = new com.yeosal.api.room.Room("기록 모임", alice);
        setId(room, 42L);

        when(entries.findById(100L)).thenReturn(Optional.of(entry));
        when(reflections.findByDailyEntry(entry)).thenReturn(Optional.empty());
        Reflection saved = new Reflection(entry, "회고");
        setId(saved, 11L);
        when(reflections.saveAndFlush(any(Reflection.class))).thenReturn(saved);
        when(roomMembers.findRoomsByUser(alice)).thenReturn(java.util.List.of(room));
        when(chatService.publishSystem(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("chat down"));

        // Should NOT throw — the per-room try/catch + REQUIRES_NEW pattern keeps
        // chat fan-out failures off the actor's commit path.
        DailyController.ReflectionDto dto = service.createReflection(alice,
                new DailyController.ReflectionCreate(100L, "회고"));
        assertThat(dto).isNotNull();
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setId(u, id);
        return u;
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

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
