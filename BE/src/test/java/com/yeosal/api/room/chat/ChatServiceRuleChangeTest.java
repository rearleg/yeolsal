package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.room.GroupMemberMinimumRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 5.4 — unit assertions for {@link ChatService#publishRuleChangeSystemMessage}.
 * Seven cases cover the AC1 helper shape, AC3 body literal, AC4 payload wire
 * contract, AC6 realtime fan-out, AC10 brand-voice lexicon, and the defensive
 * future-preset fallback (Implementation trap #6).
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceRuleChangeTest {

    private static final long ROOM_ID = 42L;
    private static final long RULE_VERSION_ID = 1001L;
    private static final long OWNER_USER_ID = 7L;

    @Mock private ChatMessageRepository messages;
    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private GroupMemberMinimumRepository minimums;
    @Mock private RealtimePublisher realtime;
    @Mock private SurvivalStateRepository survivalStates;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(messages, rooms, roomMembers, minimums, realtime, survivalStates);
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(makeRoom(ROOM_ID)));
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage saved = inv.getArgument(0);
            setField(saved, "id", 9000L);
            return saved;
        });
    }

    @Test
    @DisplayName("AC1/AC3/AC4 happy — weekendInclude=true → body + payload literals captured byte-identically")
    void publishRuleChangeSystemMessage_happyWeekendInclude_writesLockedBodyAndPayload() {
        ChatMessage saved = chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "DAILY_UPDATE", true);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        ChatMessage captured = captor.getValue();

        assertThat(captured.getKind()).isEqualTo(ChatMessageKind.SYSTEM);
        assertThat(captured.getSenderUserId()).isNull();
        assertThat(captured.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(captured.getBody())
                .isEqualTo("다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 포함");

        JsonNode payload = captured.getPayload();
        assertThat(payload.get("ruleVersionId").asText()).isEqualTo(String.valueOf(RULE_VERSION_ID));
        assertThat(payload.get("effectiveFromMonth").asText()).isEqualTo("2026-07");
        assertThat(payload.get("preview").asText()).isEqualTo("매일 업데이트, 주말 포함");

        assertThat(saved).isSameAs(captured);
    }

    @Test
    @DisplayName("AC3 weekendInclude=false flips phrase to \"주말 제외\"")
    void publishRuleChangeSystemMessage_weekendExclude_flipsPhrase() {
        chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "DAILY_UPDATE", false);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        assertThat(captor.getValue().getBody())
                .isEqualTo("다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 제외");
        assertThat(captor.getValue().getPayload().get("preview").asText())
                .isEqualTo("매일 업데이트, 주말 제외");
    }

    @Test
    @DisplayName("AC3 body prefix locked — starts with the Korean phrase + ASCII colon + ASCII space")
    void publishRuleChangeSystemMessage_bodyPrefixLocked() {
        chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "DAILY_UPDATE", true);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        String body = captor.getValue().getBody();
        assertThat(body).startsWith("다음 달부터 새 규칙이 적용됩니다: ");
        // Defense against full-width colon drift.
        assertThat(body).doesNotContain("：");
    }

    @Test
    @DisplayName("AC4 payload shape — exactly three keys, no extra (senderUserId / actorUserId banned)")
    void publishRuleChangeSystemMessage_payloadHasExactlyThreeKeys() {
        chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "DAILY_UPDATE", true);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        JsonNode payload = captor.getValue().getPayload();
        assertThat(payload.isObject()).isTrue();
        assertThat(payload.size()).isEqualTo(3);
        assertThat(payload.has("ruleVersionId")).isTrue();
        assertThat(payload.has("effectiveFromMonth")).isTrue();
        assertThat(payload.has("preview")).isTrue();
        assertThat(payload.has("senderUserId")).isFalse();
        assertThat(payload.has("actorUserId")).isFalse();
        assertThat(payload.has("previousRulePayload")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"벌금", "잃었다", "떨어졌다", "실패", "자책", "부담", "패배", "죄책감"})
    @DisplayName("AC10.2 brand-voice — body contains none of the AVOID lexicon")
    void publishRuleChangeSystemMessage_bodyAvoidsBrandLexicon(String banned) {
        chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "DAILY_UPDATE", true);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        assertThat(captor.getValue().getBody()).doesNotContain(banned);
    }

    @Test
    @DisplayName("AC6 realtime — publishChatMessage fans out the saved DTO on the chat channel")
    void publishRuleChangeSystemMessage_emitsRealtimeFanOut() {
        chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "DAILY_UPDATE", true);

        ArgumentCaptor<ChatService.MessageDto> dtoCaptor =
                ArgumentCaptor.forClass(ChatService.MessageDto.class);
        verify(realtime).publishChatMessage(eq(ROOM_ID), dtoCaptor.capture());
        ChatService.MessageDto dto = dtoCaptor.getValue();
        assertThat(dto.kind()).isEqualTo(ChatMessageKind.SYSTEM);
        assertThat(dto.body()).startsWith("다음 달부터 새 규칙이 적용됩니다: ");
    }

    @Test
    @DisplayName("Trap #6 fallback — unsupported preset is passed through as the raw enum string, no throw")
    void publishRuleChangeSystemMessage_unsupportedPreset_fallsBackToRawString() {
        chatService.publishRuleChangeSystemMessage(
                ROOM_ID, RULE_VERSION_ID, "2026-07", "WEEKLY_UPDATE", true);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(captor.capture());
        assertThat(captor.getValue().getBody())
                .isEqualTo("다음 달부터 새 규칙이 적용됩니다: WEEKLY_UPDATE, 주말 포함");
        assertThat(captor.getValue().getPayload().get("preview").asText())
                .isEqualTo("WEEKLY_UPDATE, 주말 포함");
    }

    private static Room makeRoom(long id) {
        User owner = new User("owner@example.com", "Owner", "h", AuthProvider.EMAIL);
        setField(owner, "id", OWNER_USER_ID);
        Room r = new Room("test-room", owner);
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
