package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.room.GroupMemberMinimumRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.SurvivalStateRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ChatServiceMonthlyNoSurvivorsTest {

    private ChatMessageRepository messages;
    private RoomRepository rooms;
    private RoomMemberRepository roomMembers;
    private GroupMemberMinimumRepository minimums;
    private RealtimePublisher realtime;
    private SurvivalStateRepository survivalStates;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        messages = mock(ChatMessageRepository.class);
        rooms = mock(RoomRepository.class);
        roomMembers = mock(RoomMemberRepository.class);
        minimums = mock(GroupMemberMinimumRepository.class);
        realtime = mock(RealtimePublisher.class);
        survivalStates = mock(SurvivalStateRepository.class);
        chatService = new ChatService(
                messages, rooms, roomMembers, minimums, realtime, survivalStates);

        Room room = mock(Room.class);
        when(room.getId()).thenReturn(42L);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(messages.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage saved = invocation.getArgument(0);
            setField(saved, "id", 9000L);
            setField(saved, "createdAt", Instant.parse("2026-06-07T00:00:00Z"));
            return saved;
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    @DisplayName("publishMonthlyNoSurvivorsSystemMessage — body is byte-identical to epic AC5 lock")
    void publishMonthlyNoSurvivors_lockedBody() {
        chatService.publishMonthlyNoSurvivorsSystemMessage(42L, YearMonth.of(2026, 6));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        assertThat(captor.getValue().getBody())
                .isEqualTo("이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요");
    }

    @Test
    @DisplayName("publishMonthlyNoSurvivorsSystemMessage — payload encodes yearMonth as JSON string")
    void publishMonthlyNoSurvivors_payloadYearMonth() {
        chatService.publishMonthlyNoSurvivorsSystemMessage(42L, YearMonth.of(2026, 6));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        String payloadJson = captor.getValue().getPayload().toString();
        assertThat(payloadJson).contains("\"yearMonth\"");
        assertThat(payloadJson).contains("\"2026-06\"");
    }

    @Test
    @DisplayName("publishMonthlyNoSurvivorsSystemMessage — uses SYSTEM kind with null sender")
    void publishMonthlyNoSurvivors_systemKindNullSender() {
        chatService.publishMonthlyNoSurvivorsSystemMessage(42L, YearMonth.of(2026, 6));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        ChatMessage saved = captor.getValue();
        assertThat(saved.getKind()).isEqualTo(ChatMessageKind.SYSTEM);
        assertThat(saved.getSenderUserId()).isNull();
    }

    @Test
    @DisplayName("publishMonthlyNoSurvivorsSystemMessage — REQUIRES_NEW propagation matches Story 5.4 precedent")
    void publishMonthlyNoSurvivors_requiresNewPropagation() throws Exception {
        Method method = ChatService.class.getMethod(
                "publishMonthlyNoSurvivorsSystemMessage", long.class, YearMonth.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).as("annotation present").isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("publishMonthlyNoSurvivorsSystemMessage — fans out via the same realtime chokepoint")
    void publishMonthlyNoSurvivors_realtimeFanOut() {
        chatService.publishMonthlyNoSurvivorsSystemMessage(42L, YearMonth.of(2026, 6));

        verify(realtime).publishChatMessage(eq(42L), any(ChatService.MessageDto.class));
    }
}
