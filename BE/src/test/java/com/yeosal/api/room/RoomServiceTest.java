package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomInviteRepository roomInvites;
    @Mock private InviteCodeGenerator codeGenerator;

    private final Instant now = Instant.parse("2026-04-30T10:45:32Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));

    private RoomService service;
    private User alice;
    private User bob;
    private User carol;

    @BeforeEach
    void setUp() {
        service = new RoomService(rooms, roomMembers, roomInvites, codeGenerator, clock);
        alice = makeUser(1L, "alice@example.com", "Alice");
        bob = makeUser(2L, "bob@example.com", "Bob");
        carol = makeUser(3L, "carol@example.com", "Carol");
    }

    @Test
    @DisplayName("create: persists room and inserts owner membership")
    void createPersistsRoomWithOwnerMembership() {
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        when(rooms.save(roomCaptor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        Room created = service.create(alice, "기본 방");

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getOwner()).isSameAs(alice);
        assertThat(roomCaptor.getValue().getName()).isEqualTo("기본 방");

        ArgumentCaptor<RoomMember> memberCaptor = ArgumentCaptor.forClass(RoomMember.class);
        verify(roomMembers).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getUser()).isSameAs(alice);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(RoomRole.OWNER);
    }

    @Test
    @DisplayName("create: rejects blank name")
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create(alice, "  "))
                .isInstanceOf(BadRequestException.class);
        verify(rooms, never()).save(any());
    }

    @Test
    @DisplayName("myRooms: returns rooms where user has membership")
    void myRoomsReturnsAllMemberships() {
        Room r1 = makeRoom(10L, "방1", alice);
        Room r2 = makeRoom(20L, "방2", bob);
        when(roomMembers.findByUser(alice)).thenReturn(List.of(
                new RoomMember(r1, alice, RoomRole.OWNER),
                new RoomMember(r2, alice, RoomRole.MEMBER)
        ));

        List<Room> mine = service.myRooms(alice);

        assertThat(mine).extracting(Room::getId).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    @DisplayName("createInvite: generates code, persists, only members allowed")
    void createInvitePersistsCode() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER))
        );
        when(codeGenerator.generate(any())).thenReturn("A7K9PXMQ");
        ArgumentCaptor<RoomInvite> inviteCaptor = ArgumentCaptor.forClass(RoomInvite.class);
        when(roomInvites.save(inviteCaptor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 99L));

        RoomInvite invite = service.createInvite(alice, 42L, Duration.ofDays(7));

        assertThat(invite.getCode()).isEqualTo("A7K9PXMQ");
        assertThat(inviteCaptor.getValue().getExpiresAt())
                .isEqualTo(now.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("createInvite: forbids non-members")
    void createInviteForbidsNonMembers() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createInvite(bob, 42L, Duration.ofDays(7)))
                .isInstanceOf(ForbiddenException.class);
        verify(roomInvites, never()).save(any());
    }

    @Test
    @DisplayName("joinByCode: adds the user as MEMBER when capacity allows")
    void joinByCodeAddsMember() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, now.plus(Duration.ofDays(1)));
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);

        ArgumentCaptor<RoomMember> capt = ArgumentCaptor.forClass(RoomMember.class);
        when(roomMembers.save(capt.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 7L));

        RoomMember added = service.joinByCode(bob, "A7K9PXMQ");

        assertThat(added.getUser()).isSameAs(bob);
        assertThat(capt.getValue().getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(capt.getValue().getRoom()).isSameAs(room);
    }

    @Test
    @DisplayName("joinByCode: rejects unknown / expired codes")
    void joinByCodeRejectsUnknownCode() {
        when(roomInvites.findActiveByCode("BADCODE0", now)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.joinByCode(bob, "BADCODE0"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("joinByCode: noop if already a member")
    void joinByCodeIsIdempotentForExistingMember() {
        Room room = makeRoom(42L, "기본 방", alice);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        RoomMember existing = new RoomMember(room, bob, RoomRole.MEMBER);
        lenient().when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.of(existing));

        RoomMember result = service.joinByCode(bob, "A7K9PXMQ");

        assertThat(result).isSameAs(existing);
        verify(roomMembers, never()).save(any());
    }

    @Test
    @DisplayName("joinByCode: rejects when room is full")
    void joinByCodeRejectsWhenRoomIsFull() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(roomMembers.findByRoomAndUser(room, carol)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(8L);

        assertThatThrownBy(() -> service.joinByCode(carol, "A7K9PXMQ"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("정원");
    }

    @Test
    @DisplayName("leave: member leaves; room remains")
    void leaveMember() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        RoomMember bobMember = new RoomMember(room, bob, RoomRole.MEMBER);
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.of(bobMember));

        service.leave(bob, 42L);

        verify(roomMembers).delete(bobMember);
        verify(rooms, never()).delete(any());
    }

    @Test
    @DisplayName("leave: owner with other members may not leave")
    void leaveOwnerWithMembersFails() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        RoomMember aliceMember = new RoomMember(room, alice, RoomRole.OWNER);
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(Optional.of(aliceMember));
        when(roomMembers.countByRoom(room)).thenReturn(2L);

        assertThatThrownBy(() -> service.leave(alice, 42L))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("owner");
    }

    @Test
    @DisplayName("leave: owner alone disbands the room")
    void leaveOwnerAloneDeletesRoom() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        RoomMember aliceMember = new RoomMember(room, alice, RoomRole.OWNER);
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(Optional.of(aliceMember));
        when(roomMembers.countByRoom(room)).thenReturn(1L);

        service.leave(alice, 42L);

        verify(roomMembers, times(1)).delete(aliceMember);
        verify(rooms).delete(room);
    }

    @Test
    @DisplayName("createInvite: passes a not-taken predicate to the generator")
    @SuppressWarnings("unchecked")
    void createInvitePredicateMatchesRepository() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER))
        );
        ArgumentCaptor<Predicate<String>> predicateCaptor = ArgumentCaptor.forClass(Predicate.class);
        when(codeGenerator.generate(predicateCaptor.capture())).thenReturn("A7K9PXMQ");
        when(roomInvites.save(any())).thenAnswer(inv -> setId(inv.getArgument(0), 99L));
        when(roomInvites.existsByCodeAndRevokedAtIsNull(anyString())).thenReturn(false);

        service.createInvite(alice, 42L, Duration.ofDays(7));

        Predicate<String> predicate = predicateCaptor.getValue();
        assertThat(predicate.test("A7K9PXMQ")).isFalse();
        verify(roomInvites).existsByCodeAndRevokedAtIsNull("A7K9PXMQ");
    }

    // -- helpers --
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
