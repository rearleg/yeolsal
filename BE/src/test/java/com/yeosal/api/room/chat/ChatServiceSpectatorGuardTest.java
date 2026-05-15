package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.room.GroupMemberMinimumRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 2.1 AC4 — the spectator write guard inside
 * {@link ChatService#sendUserMessage(User, long, String)}. Four cases:
 *
 * <ol>
 *   <li>SPECTATOR row present → throws {@link SpectatorWriteForbiddenException}
 *       (exact subtype, not just the parent {@link ForbiddenException}).</li>
 *   <li>ACTIVE / YELLOW / RED row → write proceeds.</li>
 *   <li>Missing survival_state row → write proceeds (defensive — V11 backfill
 *       should have created every row, but a missing one MUST NOT 500).</li>
 *   <li>{@code publishSystem(...)} for a SPECTATOR user → passes; the guard is
 *       strictly for user-authored writes.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceSpectatorGuardTest {

    @Mock private ChatMessageRepository messages;
    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private GroupMemberMinimumRepository minimums;
    @Mock private RealtimePublisher realtime;
    @Mock private SurvivalStateRepository survivalStates;

    private ChatService service;
    private User alice;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new ChatService(messages, rooms, roomMembers, minimums, realtime, survivalStates);
        alice = makeUser(1L, "Alice");
        room = makeRoom(42L, "기본 방", alice);
    }

    @Test
    @DisplayName("sendUserMessage: SPECTATOR row throws SpectatorWriteForbiddenException (NOT generic ForbiddenException code)")
    void spectatorRow_throwsSubtype() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        when(survivalStates.findByRoomIdAndUserId(42L, 1L)).thenReturn(
                Optional.of(stateOf(room, alice, SurvivalStatus.SPECTATOR)));

        assertThatThrownBy(() -> service.sendUserMessage(alice, 42L, "안녕"))
                .isInstanceOf(SpectatorWriteForbiddenException.class)
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("관전 중");
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("sendUserMessage: ACTIVE row → write proceeds (guard does not block non-spectator)")
    void activeRow_writeProceeds() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        when(survivalStates.findByRoomIdAndUserId(42L, 1L)).thenReturn(
                Optional.of(stateOf(room, alice, SurvivalStatus.ACTIVE)));
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            setId(m, 7L);
            return m;
        });

        ChatService.MessageDto dto = service.sendUserMessage(alice, 42L, "안녕");

        assertThat(dto.id()).isEqualTo(7L);
        verify(messages).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("sendUserMessage: YELLOW row → write proceeds (in-grace still posts)")
    void yellowRow_writeProceeds() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        when(survivalStates.findByRoomIdAndUserId(42L, 1L)).thenReturn(
                Optional.of(stateOf(room, alice, SurvivalStatus.YELLOW)));
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            setId(m, 8L);
            return m;
        });

        service.sendUserMessage(alice, 42L, "안녕");

        verify(messages).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("sendUserMessage: RED row → write proceeds (RED is pre-spectator, still post-capable)")
    void redRow_writeProceeds() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        when(survivalStates.findByRoomIdAndUserId(42L, 1L)).thenReturn(
                Optional.of(stateOf(room, alice, SurvivalStatus.RED)));
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            setId(m, 9L);
            return m;
        });

        service.sendUserMessage(alice, 42L, "안녕");

        verify(messages).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("sendUserMessage: missing survival_state row → write proceeds (no NPE, no 500)")
    void missingRow_writeProceeds() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        when(survivalStates.findByRoomIdAndUserId(42L, 1L)).thenReturn(Optional.empty());
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            setId(m, 10L);
            return m;
        });

        ChatService.MessageDto dto = service.sendUserMessage(alice, 42L, "안녕");

        assertThat(dto.id()).isEqualTo(10L);
        verify(messages).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("publishSystem: SPECTATOR user is NOT blocked — system messages bypass the guard (survivalStates never consulted)")
    void publishSystem_ignoresSpectatorGuard() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            setId(m, 11L);
            return m;
        });

        // No stub on survivalStates — verifies the guard is sendUserMessage-only.
        service.publishSystem(42L, ChatMessageKind.GOAL,
                "Bob님이 오늘의 목표를 작성했어요.",
                "{\"actorUserId\":2,\"date\":\"2026-05-15\"}");

        verify(messages).save(any(ChatMessage.class));
        verify(survivalStates, never()).findByRoomIdAndUserId(anyLong(), anyLong());
    }

    // ---- helpers ----

    private static SurvivalState stateOf(Room room, User user, SurvivalStatus status) {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        try {
            Field statusField = SurvivalState.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(s, status);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private static User makeUser(long id, String nickname) {
        User u = new User(nickname.toLowerCase() + "@example.com", nickname, "hash", AuthProvider.EMAIL);
        return setId(u, id);
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner);
        return setId(r, id);
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
