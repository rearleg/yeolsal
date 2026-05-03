package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.room.GroupMemberMinimum;
import com.yeosal.api.room.GroupMemberMinimumRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatMessageRepository messages;
    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private GroupMemberMinimumRepository minimums;

    private ChatService service;
    private User alice;
    private User bob;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new ChatService(messages, rooms, roomMembers, minimums);
        alice = makeUser(1L, "Alice");
        bob = makeUser(2L, "Bob");
        room = makeRoom(42L, "기본 방", alice);
    }

    @Test
    @DisplayName("list: empty cursor falls back to MAX_VALUE; reverses desc page to ascending")
    void listFirstPageReturnsAscending() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        ChatMessage m1 = new ChatMessage(42L, 1L, ChatMessageKind.USER, "1");
        ChatMessage m2 = new ChatMessage(42L, 1L, ChatMessageKind.USER, "2");
        ChatMessage m3 = new ChatMessage(42L, 1L, ChatMessageKind.USER, "3");
        setId(m1, 10L);
        setId(m2, 11L);
        setId(m3, 12L);
        // Repository returns desc by id.
        when(messages.findByRoomIdAndIdLessThanOrderByIdDesc(eq(42L), eq(Long.MAX_VALUE), any(Pageable.class)))
                .thenReturn(List.of(m3, m2, m1));

        ChatService.MessagePage page = service.list(alice, 42L, null, 30);

        assertThat(page.messages()).extracting(ChatService.MessageDto::id)
                .containsExactly(10L, 11L, 12L);
        // Page wasn't full (3 < 30), so no nextCursor.
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("list: full page exposes nextCursor = id of the oldest row in the page")
    void listFullPageExposesNextCursor() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        ChatMessage a = new ChatMessage(42L, 1L, ChatMessageKind.USER, "a");
        ChatMessage b = new ChatMessage(42L, 1L, ChatMessageKind.USER, "b");
        setId(a, 100L);
        setId(b, 90L);
        when(messages.findByRoomIdAndIdLessThanOrderByIdDesc(eq(42L), eq(Long.MAX_VALUE), any(Pageable.class)))
                .thenReturn(List.of(a, b));

        ChatService.MessagePage page = service.list(alice, 42L, null, 2);

        assertThat(page.messages()).extracting(ChatService.MessageDto::id)
                .containsExactly(90L, 100L);
        assertThat(page.nextCursor()).isEqualTo(90L);
    }

    @Test
    @DisplayName("list: forbids non-members")
    void listForbidsNonMembers() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(bob, 42L, null, 30))
                .isInstanceOf(ForbiddenException.class);
        verify(messages, never()).findByRoomIdAndIdLessThanOrderByIdDesc(any(), any(), any());
    }

    @Test
    @DisplayName("list: 404 when the room does not exist")
    void listNotFoundForUnknownRoom() {
        when(rooms.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(alice, 99L, null, 30))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("sendUserMessage: persists USER kind with the trimmed body and the sender's id")
    void sendUserMessagePersists() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        when(messages.save(captor.capture())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            setId(m, 7L);
            return m;
        });

        ChatService.MessageDto dto = service.sendUserMessage(alice, 42L, "  안녕하세요  ");

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(captor.getValue().getKind()).isEqualTo(ChatMessageKind.USER);
        assertThat(captor.getValue().getBody()).isEqualTo("안녕하세요");
        assertThat(captor.getValue().getSenderUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getRoomId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("sendUserMessage: rejects empty / whitespace-only body with 400")
    void sendUserMessageRejectsEmpty() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));

        assertThatThrownBy(() -> service.sendUserMessage(alice, 42L, "   "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("입력");
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("sendUserMessage: rejects bodies over MAX_BODY_LENGTH")
    void sendUserMessageRejectsTooLong() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        String tooLong = "a".repeat(ChatService.MAX_BODY_LENGTH + 1);

        assertThatThrownBy(() -> service.sendUserMessage(alice, 42L, tooLong))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("너무 깁니다");
    }

    @Test
    @DisplayName("publishSystem: USER kind is rejected — controllers cannot impersonate the system bus")
    void publishSystemRejectsUserKind() {
        // The USER guard fires before requireRoom, so no rooms stubbing is needed.
        assertThatThrownBy(() -> service.publishSystem(42L, ChatMessageKind.USER, "x", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("publishSystem: writes a NULL-sender row with the given kind and parsed payload")
    void publishSystemWritesNullSender() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        when(messages.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.publishSystem(42L, ChatMessageKind.GOAL,
                "Bob님이 오늘의 목표를 작성했어요.",
                "{\"actorUserId\":2,\"date\":\"2026-05-02\"}");

        ChatMessage saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo(42L);
        assertThat(saved.getSenderUserId()).isNull();
        assertThat(saved.getKind()).isEqualTo(ChatMessageKind.GOAL);
        assertThat(saved.getPayload().get("actorUserId").asInt()).isEqualTo(2);
        assertThat(saved.getPayload().get("date").asText()).isEqualTo("2026-05-02");
    }

    @Test
    @DisplayName("publishSystem: rejects non-object payload (e.g. a JSON array) with IllegalArgumentException")
    void publishSystemRejectsNonObjectPayload() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        assertThatThrownBy(() ->
                service.publishSystem(42L, ChatMessageKind.GOAL, "x", "[1,2,3]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    @DisplayName("publishSystem: rejects malformed JSON payload")
    void publishSystemRejectsMalformedPayload() {
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        assertThatThrownBy(() ->
                service.publishSystem(42L, ChatMessageKind.GOAL, "x", "{not valid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    @DisplayName("publishSystem: 404 when the room does not exist")
    void publishSystemRoomNotFound() {
        when(rooms.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                service.publishSystem(99L, ChatMessageKind.GOAL, "x", "{}"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("publishMilestonesForActor: writes 'X일 중 Y일 완료' progress per room via atomic upsert")
    void publishMilestoneFanOut() {
        Room r1 = makeRoom(42L, "방1", alice);
        Room r2 = makeRoom(43L, "방2", alice);
        when(roomMembers.findRoomsByUser(bob)).thenReturn(List.of(r1, r2));
        when(minimums.findByRoomIdAndUserId(42L, bob.getId())).thenReturn(
                Optional.of(new GroupMemberMinimum(42L, bob.getId(), (short) 15)));
        when(minimums.findByRoomIdAndUserId(43L, bob.getId())).thenReturn(
                Optional.of(new GroupMemberMinimum(43L, bob.getId(), (short) 10)));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(messages.insertMilestoneIfAbsent(eq(42L), bodyCaptor.capture(), payloadCaptor.capture()))
                .thenReturn(1);
        when(messages.insertMilestoneIfAbsent(eq(43L), any(String.class), any(String.class)))
                .thenReturn(1);

        int published = service.publishMilestonesForActor(
                bob, YearMonth.of(2026, 4), java.time.LocalDate.of(2026, 4, 7), 7);

        assertThat(published).isEqualTo(2);
        assertThat(bodyCaptor.getValue())
                .contains("Bob")
                .contains("15일 중 7일 완료");
        assertThat(payloadCaptor.getValue())
                .contains("\"userId\":\"2\"")
                .contains("\"date\":\"2026-04-07\"")
                .contains("\"completedDays\":7")
                .contains("\"requiredDays\":15");
    }

    @Test
    @DisplayName("publishMilestonesForActor: insertMilestoneIfAbsent returning 0 means already announced today")
    void publishMilestoneSkipsAlreadyAnnounced() {
        Room r1 = makeRoom(42L, "방1", alice);
        when(roomMembers.findRoomsByUser(bob)).thenReturn(List.of(r1));
        when(minimums.findByRoomIdAndUserId(42L, bob.getId())).thenReturn(
                Optional.of(new GroupMemberMinimum(42L, bob.getId(), (short) 15)));
        when(messages.insertMilestoneIfAbsent(eq(42L), any(String.class), any(String.class)))
                .thenReturn(0);

        int published = service.publishMilestonesForActor(
                bob, YearMonth.of(2026, 4), java.time.LocalDate.of(2026, 4, 7), 7);

        assertThat(published).isZero();
    }

    @Test
    @DisplayName("publishMilestonesForActor: completed=0 short-circuits without hitting the DB")
    void publishMilestoneSkipsZero() {
        // No findRoomsByUser stub — the early-return on completed<=0 should
        // fire before any room lookup happens.
        int published = service.publishMilestonesForActor(
                bob, YearMonth.of(2026, 4), java.time.LocalDate.of(2026, 4, 7), 0);

        assertThat(published).isZero();
        verify(messages, never())
                .insertMilestoneIfAbsent(any(Long.class), any(String.class), any(String.class));
    }

    @Test
    @DisplayName("publishMilestonesForActor: minimum=31 in a 30-day month caps required at month length")
    void publishMilestoneCapsThirtyOneToMonthLength() {
        Room r1 = makeRoom(42L, "방1", alice);
        when(roomMembers.findRoomsByUser(bob)).thenReturn(List.of(r1));
        when(minimums.findByRoomIdAndUserId(42L, bob.getId())).thenReturn(
                Optional.of(new GroupMemberMinimum(42L, bob.getId(), (short) 31)));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(messages.insertMilestoneIfAbsent(eq(42L), bodyCaptor.capture(), any(String.class)))
                .thenReturn(1);

        // April 2026 has 30 days. The 31-day minimum caps to 30 in the body.
        int published = service.publishMilestonesForActor(
                bob, YearMonth.of(2026, 4), java.time.LocalDate.of(2026, 4, 30), 30);

        assertThat(published).isEqualTo(1);
        assertThat(bodyCaptor.getValue()).contains("30일 중 30일 완료");
    }

    private static User makeUser(long id, String nickname) {
        User u = new User(nickname.toLowerCase() + "@example.com", nickname, "hash", AuthProvider.EMAIL);
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
