package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.survival.RecordVisibilityPrefRepository;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Service-level tests for the new reflection-edit path. The single hardest
 * invariant to defend is "edit must NOT trigger fan-out" — re-firing chat
 * system rows or push notifications would spam every roommate every time the
 * actor tweaks a typo. Each test here either drives correctness of the basic
 * update or pins the no-fan-out contract via Mockito verify(..., never()).
 */
class DailyServiceUpdateReflectionTest {

    private final Instant now = Instant.parse("2026-04-30T10:45:32Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));

    @Test
    @DisplayName("updateReflection: owner edits body — body changes, no fan-out, no milestone broadcast")
    void updateReflection_owner_updatesBody_withoutFanOut() {
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        ChatService chatService = mock(ChatService.class);
        NotificationService notifications = mock(NotificationService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        DailyService service = serviceWith(reflections, chatService, notifications, roomMembers);

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(entry, 100L);
        Reflection existing = new Reflection(entry, "원본 회고");
        setId(existing, 11L);
        setField(existing, "submittedAt", now);
        setField(existing, "updatedAt", now);

        when(reflections.findById(11L)).thenReturn(Optional.of(existing));

        DailyController.ReflectionDto dto = service.updateReflection(
                alice, 11L, new DailyController.ReflectionUpdate("수정된 회고"));

        assertThat(dto.body()).isEqualTo("수정된 회고");
        assertThat(existing.getBody()).isEqualTo("수정된 회고");

        // Critical invariant: edits must never re-trigger the chat fan-out
        // path — roommates already received the REFLECTION + MILESTONE rows
        // on the first submit; re-firing on every typo fix would spam them.
        verify(chatService, never()).publishSystem(anyLong(), any(), anyString(), anyString());
        verify(chatService, never()).publishMilestonesForActor(
                any(User.class), any(YearMonth.class), any(LocalDate.class),
                org.mockito.ArgumentMatchers.anyInt());
        verify(notifications, never()).sendEvent(
                any(User.class), any(), anyString(), anyString(), anyString(), any());
        verify(roomMembers, never()).findRoomMates(any(User.class));
        verify(roomMembers, never()).findRoomsByUser(any(User.class));
    }

    @Test
    @DisplayName("updateReflection: non-owner is rejected with ForbiddenException")
    void updateReflection_nonOwner_throwsForbidden() {
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        ChatService chatService = mock(ChatService.class);
        DailyService service = serviceWith(reflections, chatService,
                mock(NotificationService.class), mock(RoomMemberRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        User bob = makeUser(2L, "bob@example.com", "Bob");
        DailyEntry aliceEntry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(aliceEntry, 100L);
        Reflection aliceReflection = new Reflection(aliceEntry, "Alice의 회고");
        setId(aliceReflection, 11L);

        when(reflections.findById(11L)).thenReturn(Optional.of(aliceReflection));

        assertThatThrownBy(() -> service.updateReflection(
                bob, 11L, new DailyController.ReflectionUpdate("훔쳐서 수정")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("회고");

        // No mutation, no fan-out — must be a hard reject.
        assertThat(aliceReflection.getBody()).isEqualTo("Alice의 회고");
        verify(chatService, never()).publishSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateReflection: blank body is rejected with BadRequestException")
    void updateReflection_blankBody_throwsBadRequest() {
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        DailyService service = serviceWith(reflections, mock(ChatService.class),
                mock(NotificationService.class), mock(RoomMemberRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        setId(entry, 100L);
        Reflection existing = new Reflection(entry, "원본");
        setId(existing, 11L);
        when(reflections.findById(11L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateReflection(
                alice, 11L, new DailyController.ReflectionUpdate("   ")))
                .isInstanceOf(BadRequestException.class);

        assertThat(existing.getBody()).isEqualTo("원본");
    }

    @Test
    @DisplayName("updateReflection: missing reflection id throws NotFoundException")
    void updateReflection_missing_throwsNotFound() {
        ReflectionRepository reflections = mock(ReflectionRepository.class);
        DailyService service = serviceWith(reflections, mock(ChatService.class),
                mock(NotificationService.class), mock(RoomMemberRepository.class));

        User alice = makeUser(1L, "alice@example.com", "Alice");
        when(reflections.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateReflection(
                alice, 999L, new DailyController.ReflectionUpdate("아무거나")))
                .isInstanceOf(NotFoundException.class);
    }

    private DailyService serviceWith(
            ReflectionRepository reflections,
            ChatService chatService,
            NotificationService notifications,
            RoomMemberRepository roomMembers) {
        DailyEntryRepository entries = mock(DailyEntryRepository.class);
        TodoItemRepository todos = mock(TodoItemRepository.class);
        EntryDateResolver entryDateResolver = mock(EntryDateResolver.class);
        GateRule gateRule = mock(GateRule.class);
        return new DailyService(
                entries, todos, reflections, entryDateResolver, gateRule,
                clock, roomMembers, notifications, chatService,
                mock(RecordVisibilityPrefRepository.class),
                mock(SurvivalStateRepository.class));
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
