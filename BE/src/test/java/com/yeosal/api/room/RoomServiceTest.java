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
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import jakarta.persistence.EntityManager;
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

    private final Instant now = Instant.parse("2026-04-30T10:45:32Z");
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));

    private RoomService service;
    private User alice;
    private User bob;
    private User carol;

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
                entityManager);
        alice = makeUser(1L, "alice@example.com", "Alice");
        bob = makeUser(2L, "bob@example.com", "Bob");
        carol = makeUser(3L, "carol@example.com", "Carol");
        // Default save behavior: echo the argument back. RoomService now reads
        // the saved RoomMember.joinedAt to seed survival_state.grace_ends_at
        // (AC2 — same instant for both writes), so tests that don't otherwise
        // stub the save would NPE without this default.
        lenient().when(roomMembers.save(any(RoomMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("create: persists room, inserts owner membership, returns summary")
    void createPersistsRoomWithOwnerMembership() {
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        when(rooms.save(roomCaptor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        RoomService.RoomSummary created = service.create(alice, "기본 방");

        assertThat(created.id()).isEqualTo(42L);
        assertThat(created.name()).isEqualTo("기본 방");
        assertThat(created.ownerId()).isEqualTo(1L);
        assertThat(roomCaptor.getValue().getName()).isEqualTo("기본 방");

        ArgumentCaptor<RoomMember> memberCaptor = ArgumentCaptor.forClass(RoomMember.class);
        verify(roomMembers).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getUser()).isSameAs(alice);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(RoomRole.OWNER);
        verify(roomRuleVersions).insertDefaultIfAbsent(42L, "2026-04", 1L);
    }

    @Test
    @DisplayName("create: rejects blank name")
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create(alice, "  "))
                .isInstanceOf(BadRequestException.class);
        verify(rooms, never()).save(any());
    }

    @Test
    @DisplayName("myRooms: returns RoomSummary list, evaluating lazy fields inside transaction")
    void myRoomsReturnsAllMemberships() {
        Room r1 = makeRoom(10L, "방1", alice);
        Room r2 = makeRoom(20L, "방2", bob);
        when(rooms.findById(10L)).thenReturn(Optional.of(r1));
        when(rooms.findById(20L)).thenReturn(Optional.of(r2));
        when(roomMembers.findByUser(alice)).thenReturn(List.of(
                new RoomMember(r1, alice, RoomRole.OWNER),
                new RoomMember(r2, alice, RoomRole.MEMBER)
        ));

        List<RoomService.RoomSummary> mine = service.myRooms(alice);

        assertThat(mine)
                .extracting(RoomService.RoomSummary::id)
                .containsExactlyInAnyOrder(10L, 20L);
        assertThat(mine)
                .extracting(RoomService.RoomSummary::name)
                .containsExactlyInAnyOrder("방1", "방2");
        assertThat(mine)
                .extracting(RoomService.RoomSummary::ownerId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("members: returns MemberSummary list with nickname pre-resolved")
    void membersReturnsSummaries() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER))
        );
        when(roomMembers.findByRoom(room)).thenReturn(List.of(
                new RoomMember(room, alice, RoomRole.OWNER),
                new RoomMember(room, bob, RoomRole.MEMBER)
        ));

        List<RoomService.MemberSummary> result = service.members(alice, 42L);

        assertThat(result)
                .extracting(RoomService.MemberSummary::userId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(result)
                .extracting(RoomService.MemberSummary::nickname)
                .containsExactlyInAnyOrder("Alice", "Bob");
        assertThat(result)
                .extracting(RoomService.MemberSummary::roomId)
                .containsOnly(42L);
    }

    @Test
    @DisplayName("createInvite: generates code, persists, returns InviteSummary")
    void createInvitePersistsCode() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER))
        );
        when(codeGenerator.generate(any())).thenReturn("A7K9PXMQ");
        ArgumentCaptor<RoomInvite> inviteCaptor = ArgumentCaptor.forClass(RoomInvite.class);
        when(roomInvites.save(inviteCaptor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 99L));

        RoomService.InviteSummary invite = service.createInvite(alice, 42L, Duration.ofDays(7));

        assertThat(invite.code()).isEqualTo("A7K9PXMQ");
        assertThat(invite.roomId()).isEqualTo(42L);
        assertThat(invite.id()).isEqualTo(99L);
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
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);

        ArgumentCaptor<RoomMember> capt = ArgumentCaptor.forClass(RoomMember.class);
        when(roomMembers.save(capt.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 7L));

        RoomService.MemberSummary added = service.joinByCode(bob, "A7K9PXMQ");

        assertThat(added.userId()).isEqualTo(bob.getId());
        assertThat(added.roomId()).isEqualTo(42L);
        assertThat(added.role()).isEqualTo(RoomRole.MEMBER);
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
    @DisplayName("joinByCode: noop if already a member, returns existing summary")
    void joinByCodeIsIdempotentForExistingMember() {
        Room room = makeRoom(42L, "기본 방", alice);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        RoomMember existing = new RoomMember(room, bob, RoomRole.MEMBER);
        lenient().when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.of(existing));

        RoomService.MemberSummary result = service.joinByCode(bob, "A7K9PXMQ");

        assertThat(result.userId()).isEqualTo(bob.getId());
        assertThat(result.roomId()).isEqualTo(42L);
        assertThat(result.role()).isEqualTo(RoomRole.MEMBER);
        verify(roomMembers, never()).save(any());
    }

    @Test
    @DisplayName("joinByCode: rejects when room is full")
    void joinByCodeRejectsWhenRoomIsFull() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
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

    @Test
    @DisplayName("create: writes a GroupMemberMinimum row mirroring the chosen minimum")
    void createWritesMinimumRow() {
        when(rooms.save(any(Room.class))).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        service.create(alice, "기본 방", (short) 15);

        ArgumentCaptor<GroupMemberMinimum> captor = ArgumentCaptor.forClass(GroupMemberMinimum.class);
        verify(minimums).save(captor.capture());
        GroupMemberMinimum saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo(42L);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getMinDailyGoalDays()).isEqualTo((short) 15);
        assertThat(saved.getWarningCount()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("create: rejects minimum that isn't in the {10, 15, 20, 31} whitelist")
    void createRejectsBadMinimum() {
        assertThatThrownBy(() -> service.create(alice, "기본 방", (short) 7))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10/15/20");
        verify(rooms, never()).save(any());
        verify(minimums, never()).save(any());
    }

    @Test
    @DisplayName("create: backwards-compat overload defaults to 10")
    void createBackwardsCompatDefaultsTo10() {
        ArgumentCaptor<Room> captor = ArgumentCaptor.forClass(Room.class);
        when(rooms.save(captor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        RoomService.RoomSummary summary = service.create(alice, "기본 방");

        assertThat(captor.getValue().getMinDailyGoalDays()).isEqualTo((short) 10);
        assertThat(summary.minDailyGoalDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("joinByCode: persists a member-minimum row mirroring the room's current minimum")
    void joinByCodePersistsMinimum() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 20);
        room.setMaxMembers((short) 8);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, now.plus(Duration.ofDays(1)));
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);
        when(roomMembers.save(any(RoomMember.class))).thenAnswer(inv -> setId(inv.getArgument(0), 7L));
        when(minimums.save(any(GroupMemberMinimum.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomService.MemberSummary added = service.joinByCode(bob, "A7K9PXMQ");

        ArgumentCaptor<GroupMemberMinimum> captor = ArgumentCaptor.forClass(GroupMemberMinimum.class);
        verify(minimums).save(captor.capture());
        assertThat(captor.getValue().getMinDailyGoalDays()).isEqualTo((short) 20);
        assertThat(captor.getValue().getUserId()).isEqualTo(bob.getId());
        assertThat(added.currentMinimum()).isEqualTo(20);
        assertThat(added.warningCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("joinByCode: idempotent path also exposes the existing minimum")
    void joinByCodeIdempotentSurfacesExistingMinimum() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 20);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, null);
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        RoomMember existing = new RoomMember(room, bob, RoomRole.MEMBER);
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.of(existing));
        GroupMemberMinimum existingMin = new GroupMemberMinimum(42L, bob.getId(), (short) 31);
        existingMin.setWarningCount((short) 1);
        when(minimums.findByRoomIdAndUserId(42L, bob.getId())).thenReturn(Optional.of(existingMin));

        RoomService.MemberSummary result = service.joinByCode(bob, "A7K9PXMQ");

        assertThat(result.currentMinimum()).isEqualTo(31);
        assertThat(result.warningCount()).isEqualTo(1);
        verify(minimums, never()).save(any());
    }

    @Test
    @DisplayName("members: batches the minimum lookup by roomId and decorates each MemberSummary")
    void membersDecoratesWithMinimum() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 15);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER))
        );
        when(roomMembers.findByRoom(room)).thenReturn(List.of(
                new RoomMember(room, alice, RoomRole.OWNER),
                new RoomMember(room, bob, RoomRole.MEMBER)
        ));
        GroupMemberMinimum aliceMin = new GroupMemberMinimum(42L, alice.getId(), (short) 15);
        GroupMemberMinimum bobMin = new GroupMemberMinimum(42L, bob.getId(), (short) 31);
        bobMin.setWarningCount((short) 2);
        when(minimums.findByRoomId(42L)).thenReturn(List.of(aliceMin, bobMin));

        List<RoomService.MemberSummary> result = service.members(alice, 42L);

        assertThat(result)
                .extracting(RoomService.MemberSummary::userId, RoomService.MemberSummary::currentMinimum, RoomService.MemberSummary::warningCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(alice.getId(), 15, 0),
                        org.assertj.core.groups.Tuple.tuple(bob.getId(), 31, 2)
                );
        // Single batched lookup, not per-member.
        verify(minimums, times(1)).findByRoomId(42L);
    }

    @Test
    @DisplayName("updateMyMinimum: persists raised minimum on the existing member row")
    void updateMyMinimumRaisesExistingRow() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 10);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        GroupMemberMinimum existing = new GroupMemberMinimum(42L, 1L, (short) 10);
        when(minimums.findByRoomIdAndUserId(42L, 1L)).thenReturn(Optional.of(existing));

        RoomService.MemberSummary result = service.updateMyMinimum(alice, 42L, 20);

        assertThat(existing.getMinDailyGoalDays()).isEqualTo((short) 20);
        assertThat(result.currentMinimum()).isEqualTo(20);
        // No explicit save — JPA dirty-check on commit handles persistence.
        verify(minimums, never()).save(any());
    }

    @Test
    @DisplayName("updateMyMinimum: lazily creates a member row via race-safe upsert when none exists")
    void updateMyMinimumLazilyCreatesRow() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 10);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));
        // First findById is empty (legacy/missing row), insertIfAbsent succeeds,
        // and the second findById then sees the just-inserted row.
        GroupMemberMinimum freshlyCreated = new GroupMemberMinimum(42L, 1L, (short) 10);
        when(minimums.findByRoomIdAndUserId(42L, 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(freshlyCreated));
        when(minimums.insertIfAbsent(42L, 1L, (short) 10)).thenReturn(1);

        RoomService.MemberSummary result = service.updateMyMinimum(alice, 42L, 15);

        verify(minimums).insertIfAbsent(42L, 1L, (short) 10);
        // Service raises the fresh row in-place; JPA dirty-check would persist.
        assertThat(freshlyCreated.getMinDailyGoalDays()).isEqualTo((short) 15);
        assertThat(result.currentMinimum()).isEqualTo(15);
    }

    @Test
    @DisplayName("updateMyMinimum: rejects values below the room floor")
    void updateMyMinimumRejectsBelowRoomFloor() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 20);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));

        assertThatThrownBy(() -> service.updateMyMinimum(alice, 42L, 15))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("그룹 최소 기준");
        verify(minimums, never()).save(any());
    }

    @Test
    @DisplayName("updateMyMinimum: rejects non-whitelist values")
    void updateMyMinimumRejectsBadValue() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMinDailyGoalDays((short) 10);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER)));

        assertThatThrownBy(() -> service.updateMyMinimum(alice, 42L, 7))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10/15/20");
    }

    @Test
    @DisplayName("updateMyMinimum: forbids non-members")
    void updateMyMinimumForbidsNonMembers() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMyMinimum(bob, 42L, 20))
                .isInstanceOf(ForbiddenException.class);
    }

    // -- Story 1.1: max_members widening + survival_state on join --

    @Test
    @DisplayName("create(maxMembers=12): persists Room.maxMembers and initializes survival_state for owner")
    void createPersistsMaxMembersAndSurvivalState() {
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        when(rooms.save(roomCaptor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        RoomService.RoomSummary created = service.create(alice, "기본 방", 10, 12);

        assertThat(created.maxMembers()).isEqualTo(12);
        assertThat(roomCaptor.getValue().getMaxMembers()).isEqualTo((short) 12);
        // AC5 — leader-of-record invariant.
        assertThat(roomCaptor.getValue().getOwner().getId()).isEqualTo(alice.getId());
        verify(survivalState, times(1)).initializeOnJoin(roomCaptor.getValue(), alice, now);
        // Story 3.1 — fresh room must seed its room_point_pool row so
        // revival flows do not throw on `selectForUpdate(roomId)`.
        ArgumentCaptor<com.yeosal.api.revival.RoomPointPool> poolCaptor =
                ArgumentCaptor.forClass(com.yeosal.api.revival.RoomPointPool.class);
        verify(roomPointPool, times(1)).save(poolCaptor.capture());
        assertThat(poolCaptor.getValue().getRoomId()).isEqualTo(42L);
        assertThat(poolCaptor.getValue().getTotal()).isZero();
    }

    @Test
    @DisplayName("create: rejects maxMembers below 2 with BadRequest")
    void createRejectsMaxMembersBelow2() {
        assertThatThrownBy(() -> service.create(alice, "기본 방", 10, 1))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("정원");
        verify(rooms, never()).save(any());
        verify(survivalState, never()).initializeOnJoin(any(), any(), any());
    }

    @Test
    @DisplayName("create: rejects maxMembers above 30 with BadRequest")
    void createRejectsMaxMembersAbove30() {
        assertThatThrownBy(() -> service.create(alice, "기본 방", 10, 31))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("정원");
        verify(rooms, never()).save(any());
        verify(survivalState, never()).initializeOnJoin(any(), any(), any());
    }

    @Test
    @DisplayName("create: accepts maxMembers at boundaries 2 and 30")
    void createAcceptsBoundaries() {
        when(rooms.save(any(Room.class))).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        service.create(alice, "방2", 10, 2);
        service.create(alice, "방30", 10, 30);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(rooms, times(2)).save(roomCaptor.capture());
        assertThat(roomCaptor.getAllValues())
                .extracting(Room::getMaxMembers)
                .containsExactly((short) 2, (short) 30);
    }

    @Test
    @DisplayName("create: 3-arg overload defaults maxMembers to 12")
    void createBackwardsCompatDefaultsMaxMembersTo12() {
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        when(rooms.save(roomCaptor.capture())).thenAnswer(inv -> setId(inv.getArgument(0), 42L));

        service.create(alice, "기본 방", 10);

        assertThat(roomCaptor.getValue().getMaxMembers()).isEqualTo((short) 12);
    }

    @Test
    @DisplayName("joinByCode: initializes survival_state for the newly added member")
    void joinByCodeInitializesSurvivalState() {
        Room room = makeRoom(42L, "기본 방", alice);
        room.setMaxMembers((short) 12);
        room.setMinDailyGoalDays((short) 10);
        RoomInvite invite = new RoomInvite(room, "A7K9PXMQ", alice, now.plus(Duration.ofDays(1)));
        when(roomInvites.findActiveByCode("A7K9PXMQ", now)).thenReturn(Optional.of(invite));
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(Optional.empty());
        when(roomMembers.countByRoom(room)).thenReturn(1L);
        when(roomMembers.save(any(RoomMember.class))).thenAnswer(inv -> setId(inv.getArgument(0), 7L));
        when(minimums.save(any(GroupMemberMinimum.class))).thenAnswer(inv -> inv.getArgument(0));

        service.joinByCode(bob, "A7K9PXMQ");

        verify(survivalState, times(1)).initializeOnJoin(room, bob, now);
    }

    @Test
    @DisplayName("requireRoom: no due promotion returns loaded room without refresh")
    void requireRoomNoPromotionDoesNotRefresh() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(capPromotion.promotePendingCapIfDue(42L)).thenReturn(false);

        Room result = service.requireRoom(42L);

        assertThat(result).isSameAs(room);
        verify(entityManager, never()).refresh(any());
    }

    @Test
    @DisplayName("requireRoom: due promotion refreshes the managed room instance")
    void requireRoomPromotionRefreshesManagedRoom() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(capPromotion.promotePendingCapIfDue(42L)).thenReturn(true);

        Room result = service.requireRoom(42L);

        assertThat(result).isSameAs(room);
        verify(entityManager).refresh(room);
    }

    @Test
    @DisplayName("requireRoomForUpdate: promotes first, then acquires row lock")
    void requireRoomForUpdateLocksAfterPromotion() {
        Room stale = makeRoom(42L, "기본 방", alice);
        Room locked = makeRoom(42L, "기본 방", alice);
        locked.setMaxMembers((short) 20);
        when(rooms.findById(42L)).thenReturn(Optional.of(stale));
        when(capPromotion.promotePendingCapIfDue(42L)).thenReturn(true);
        when(rooms.findByIdForUpdate(42L)).thenReturn(Optional.of(locked));

        Room result = service.requireRoomForUpdate(42L);

        assertThat(result).isSameAs(locked);
        verify(entityManager).refresh(stale);
        verify(rooms).findByIdForUpdate(42L);
    }

    @Test
    @DisplayName("members: masks RED cooldown from non-leader viewers")
    void membersMasksRedCooldownForNonLeader() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, bob)).thenReturn(
                Optional.of(new RoomMember(room, bob, RoomRole.MEMBER))
        );
        when(roomMembers.findByRoom(room)).thenReturn(List.of(
                new RoomMember(room, alice, RoomRole.OWNER),
                new RoomMember(room, bob, RoomRole.MEMBER),
                new RoomMember(room, carol, RoomRole.MEMBER)
        ));
        SurvivalState carolState = new SurvivalState(room, carol, now.minus(Duration.ofDays(20)));
        setField(carolState, "status", SurvivalStatus.RED);
        setField(carolState, "broadVisibilityAt", now.plus(Duration.ofHours(1)));
        when(survivalStates.findByRoomIdFetchingUser(42L)).thenReturn(List.of(carolState));

        List<RoomService.MemberSummary> result = service.members(bob, 42L);

        assertThat(result)
                .filteredOn(m -> m.userId() == carol.getId())
                .extracting(RoomService.MemberSummary::survivalStatus)
                .containsExactly(SurvivalStatus.ACTIVE);
    }

    @Test
    @DisplayName("members: leader can see RED during cooldown")
    void membersLeaderSeesRedCooldown() {
        Room room = makeRoom(42L, "기본 방", alice);
        when(rooms.findById(42L)).thenReturn(Optional.of(room));
        when(roomMembers.findByRoomAndUser(room, alice)).thenReturn(
                Optional.of(new RoomMember(room, alice, RoomRole.OWNER))
        );
        when(roomMembers.findByRoom(room)).thenReturn(List.of(
                new RoomMember(room, alice, RoomRole.OWNER),
                new RoomMember(room, carol, RoomRole.MEMBER)
        ));
        SurvivalState carolState = new SurvivalState(room, carol, now.minus(Duration.ofDays(20)));
        setField(carolState, "status", SurvivalStatus.RED);
        setField(carolState, "broadVisibilityAt", now.plus(Duration.ofHours(1)));
        when(survivalStates.findByRoomIdFetchingUser(42L)).thenReturn(List.of(carolState));

        List<RoomService.MemberSummary> result = service.members(alice, 42L);

        assertThat(result)
                .filteredOn(m -> m.userId() == carol.getId())
                .extracting(RoomService.MemberSummary::survivalStatus)
                .containsExactly(SurvivalStatus.RED);
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
        setField(entity, "id", id);
        return entity;
    }

    private static void setField(Object entity, String name, Object value) {
        try {
            Field f = findField(entity.getClass(), name);
            f.setAccessible(true);
            f.set(entity, value);
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
