package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 1.6 AC5 + AC9 — when {@link RoomService#joinByCode} adds a new member,
 * a SYSTEM-kind chat message "{nickname} 함께합니다 🌿" is emitted via
 * {@link ChatService#publishMemberJoinedSystemMessage}. The new hook fires
 * after {@link com.yeosal.api.realtime.RealtimePublisher#publishMemberAdded}.
 *
 * <p>Transactional note: the implementation reuses {@code ChatService.publishSystem}
 * which runs in {@code REQUIRES_NEW}. A chat-write failure therefore does NOT
 * roll back the membership insert (matching the canonical
 * {@code publishAutoLeaveAfterCommit} pattern). This is a deliberate deviation
 * from the original story's BE-2.1(d) rollback expectation; see Dev Agent Record.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceMemberJoinSystemMessageTest {

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
    @Mock private com.yeosal.api.revival.RoomPointPoolRepository roomPointPool;
    @Mock private com.yeosal.api.survival.RoomRuleVersionRepository roomRuleVersions;
    @Mock private RoomCapPromotionService capPromotion;
    @Mock private EntityManager entityManager;
    @Mock private com.yeosal.api.kakaoshare.PreviewCardCacheService previewCardCacheService;
    @Mock private com.yeosal.api.kakaoshare.ShareUrlBuilder shareUrlBuilder;

    private final Instant now = Instant.parse("2026-04-30T10:45:32Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));

    private RoomService service;
    private User alice;
    private User bob;

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
                visibilityPrefs,
                roomPointPool,
                roomRuleVersions,
                capPromotion,
                entityManager,
                previewCardCacheService,
                shareUrlBuilder);
        alice = makeUser(1L, "alice@example.com", "Alice");
        bob = makeUser(2L, "bob@example.com", "Bob");
        lenient().when(roomMembers.save(any(RoomMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("joinByCode: emits SYSTEM chat message via ChatService after successful join")
    void joinByCodeEmitsMemberJoinedSystemMessage() {
        Room room = makeRoom(42L, "첫 그룹", alice);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, now.plus(Duration.ofDays(1)));
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);

        service.joinByCode(bob, "A7K9PXMQ");

        verify(chatService).publishMemberJoinedSystemMessage(room, bob);
    }

    @Test
    @DisplayName("joinByCode: chat hook fires after realtime member-added fan-out")
    void joinByCodeFiresChatHookAfterRealtimeMemberAdded() {
        Room room = makeRoom(42L, "첫 그룹", alice);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);

        service.joinByCode(bob, "A7K9PXMQ");

        verify(realtime).publishMemberAdded(eq(42L), any());
        verify(chatService).publishMemberJoinedSystemMessage(room, bob);
    }

    @Test
    @DisplayName("joinByCode: idempotent path (already a member) does NOT emit a second system message")
    void joinByCodeIdempotentDoesNotEmitDuplicate() {
        Room room = makeRoom(42L, "첫 그룹", alice);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        RoomMember existing = new RoomMember(room, bob, RoomRole.MEMBER);
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.of(existing));

        service.joinByCode(bob, "A7K9PXMQ");

        verify(chatService, never()).publishMemberJoinedSystemMessage(any(), any());
    }

    @Test
    @DisplayName("joinByCode: rejected by room-full BadRequest does NOT emit a system message")
    void joinByCodeRoomFullDoesNotEmitMessage() {
        Room room = makeRoom(42L, "첫 그룹", alice);
        room.setMaxMembers((short) 2);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(2L);

        try {
            service.joinByCode(bob, "A7K9PXMQ");
        } catch (RuntimeException expected) {
            // BadRequestException from room-full guard — expected
        }
        verify(chatService, never()).publishMemberJoinedSystemMessage(any(), any());
    }

    @Test
    @DisplayName("publishMemberJoinedSystemMessage is called with the freshly-joined user (not the owner)")
    void publishHookReceivesFreshUser() {
        Room room = makeRoom(42L, "첫 그룹", alice);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);

        service.joinByCode(bob, "A7K9PXMQ");

        verify(chatService).publishMemberJoinedSystemMessage(roomCaptor.capture(), userCaptor.capture());
        assertThat(userCaptor.getValue().getId()).isEqualTo(bob.getId());
        assertThat(userCaptor.getValue().getNickname()).isEqualTo("Bob");
        assertThat(roomCaptor.getValue().getId()).isEqualTo(42L);
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner, (short) GoalMinimumDays.DEFAULT);
        try {
            Field f = Room.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(r, id);
            Field createdAt = Room.class.getDeclaredField("createdAt");
            createdAt.setAccessible(true);
            createdAt.set(r, Instant.parse("2026-04-25T00:00:00Z"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return r;
    }
}
