package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomService;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Story 5.1 — Mockito unit assertions for {@link RoomRuleService}. Twelve
 * cases cover the AC13 minimum-10 contract plus the calendar-month KST
 * trap boundaries (Implementation traps #1 + #13).
 */
@ExtendWith(MockitoExtension.class)
class RoomRuleServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long ROOM_ID = 42L;
    private static final long LEADER_ID = 7L;
    private static final long MEMBER_ID = 11L;
    private static final long STRANGER_ID = 99L;
    private static final Instant DEFAULT_NOW =
            LocalDateTime.of(2026, 4, 15, 12, 0).atZone(KST).toInstant();

    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomRuleVersionRepository ruleVersions;
    @Mock private RoomService roomService;
    @Mock private ChatService chatService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RoomRuleService service;
    private User leader;
    private User member;
    private User stranger;
    private Room room;

    @BeforeEach
    void setUp() {
        service = build(Clock.fixed(DEFAULT_NOW, KST));
        leader = makeUser(LEADER_ID, "leader@example.com", "Leader");
        member = makeUser(MEMBER_ID, "member@example.com", "Member");
        stranger = makeUser(STRANGER_ID, "stranger@example.com", "Stranger");
        room = makeRoom(ROOM_ID, leader);
    }

    @AfterEach
    void tearDown() {
        // Some cases pin tx synchronization to validate the publishAfterCommit
        // branch — clear it before the next case so leakage cannot bleed into
        // an inline-path assertion.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private RoomRuleService build(Clock clock) {
        return new RoomRuleService(
                rooms, roomMembers, ruleVersions, roomService, clock, objectMapper, chatService);
    }

    // ---------- updateRule ----------

    @Test
    @DisplayName("updateRule happy insert — leader stages a new next-month rule, returns DTO")
    void updateRule_insertsAndReturnsDto() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(1001L, "2026-05", "DAILY_UPDATE", false)));

        RoomRuleVersionDto dto = service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false);

        assertThat(dto.id()).isEqualTo(1001L);
        assertThat(dto.preset()).isEqualTo("DAILY_UPDATE");
        assertThat(dto.weekendInclude()).isFalse();
        assertThat(dto.effectiveFromMonth()).isEqualTo("2026-05");
        assertThat(dto.createdByUserId()).isEqualTo(LEADER_ID);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(ruleVersions).upsertRule(eq(ROOM_ID), eq("2026-05"), payloadCaptor.capture(), eq(LEADER_ID));
        assertThat(payloadCaptor.getValue())
                .contains("\"preset\":\"DAILY_UPDATE\"")
                .contains("\"weekendInclude\":false");
    }

    @Test
    @DisplayName("updateRule happy replace — same-month re-edit returns DTO from upserted row")
    void updateRule_replaceExistingRowReturnsLatestDto() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(1001L, "2026-05", "DAILY_UPDATE", true)));

        RoomRuleVersionDto dto = service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", true);

        assertThat(dto.weekendInclude()).isTrue();
        verify(ruleVersions).upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID));
    }

    @Test
    @DisplayName("updateRule leader-only — RoomService.requireLeader throws → ForbiddenException propagates, no upsert")
    void updateRule_nonLeader_throwsForbidden() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        doThrow(new ForbiddenException("방장 권한이 필요합니다."))
                .when(roomService).requireLeader(any(Room.class), eq(member));

        assertThatThrownBy(() -> service.updateRule(member, ROOM_ID, "DAILY_UPDATE", false))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("방장 권한이 필요합니다.");

        verify(ruleVersions, never()).upsertRule(anyLong(), anyString(), anyString(), anyLong());
        verify(ruleVersions, never()).findByRoomIdAndEffectiveFromMonth(anyLong(), anyString());
    }

    @Test
    @DisplayName("updateRule unknown room → NotFoundException, no leader check, no upsert")
    void updateRule_unknownRoom_throwsNotFound() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("방을 찾을 수 없습니다.");

        verify(roomService, never()).requireLeader(any(Room.class), any(User.class));
        verify(ruleVersions, never()).upsertRule(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("updateRule invalid preset (\"WEEKLY\") → BadRequestException, no upsert")
    void updateRule_invalidPreset_throwsBadRequest() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.updateRule(leader, ROOM_ID, "WEEKLY", false))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DAILY_UPDATE");

        verify(ruleVersions, never()).upsertRule(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("nextMonth at 2026-04-30 23:59 KST returns \"2026-05\" (calendar-month KST trap #1)")
    void nextMonth_atLateAprilKst_returnsMay() {
        service = build(Clock.fixed(
                LocalDateTime.of(2026, 4, 30, 23, 59, 30).atZone(KST).toInstant(), KST));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(1L, "2026-05", "DAILY_UPDATE", false)));

        RoomRuleVersionDto dto = service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false);

        assertThat(dto.effectiveFromMonth()).isEqualTo("2026-05");
    }

    @Test
    @DisplayName("nextMonth at 2026-05-01 02:00 KST returns \"2026-06\" (calendar already May)")
    void nextMonth_atEarlyMayKstBeforeEvaluator_returnsJune() {
        service = build(Clock.fixed(
                LocalDateTime.of(2026, 5, 1, 2, 0, 0).atZone(KST).toInstant(), KST));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-06"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-06"))
                .thenReturn(Optional.of(stubRow(1L, "2026-06", "DAILY_UPDATE", false)));

        RoomRuleVersionDto dto = service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false);

        assertThat(dto.effectiveFromMonth()).isEqualTo("2026-06");
    }

    @Test
    @DisplayName("updateRule after upsert — DB row missing → IllegalStateException (data-shape bug)")
    void updateRule_missingRowAfterUpsert_throwsIllegalState() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing after upsert");
    }

    // ---------- Story 5.4 broadcast hook ----------

    @Test
    @DisplayName("Story 5.4 — happy insert emits broadcast with saved row's id, month, preset, weekendInclude")
    void updateRule_emitsRuleChangeBroadcast_onHappyInsert() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(1001L, "2026-05", "DAILY_UPDATE", false)));

        service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false);

        verify(chatService).publishRuleChangeSystemMessage(
                eq(ROOM_ID), eq(1001L), eq("2026-05"), eq("DAILY_UPDATE"), eq(false));
    }

    @Test
    @DisplayName("Story 5.4 — replace flow (same nextMonth re-edit) still fires broadcast (no dedupe)")
    void updateRule_emitsRuleChangeBroadcast_onReplace() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(2002L, "2026-05", "DAILY_UPDATE", true)));

        service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", true);
        service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", true);

        verify(chatService, times(2)).publishRuleChangeSystemMessage(
                eq(ROOM_ID), eq(2002L), eq("2026-05"), eq("DAILY_UPDATE"), eq(true));
    }

    @Test
    @DisplayName("Story 5.4 — non-leader path never reaches the broadcast hook")
    void updateRule_doesNotEmitBroadcast_whenNonLeader() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        doThrow(new ForbiddenException("방장 권한이 필요합니다."))
                .when(roomService).requireLeader(any(Room.class), eq(member));

        assertThatThrownBy(() -> service.updateRule(member, ROOM_ID, "DAILY_UPDATE", false))
                .isInstanceOf(ForbiddenException.class);

        Mockito.verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Story 5.4 — inside an active transaction synchronization, broadcast is deferred until afterCommit fires")
    void updateRule_registersAfterCommitSynchronization_whenInsideTransaction() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(3003L, "2026-05", "DAILY_UPDATE", false)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", false);

            // The lambda must be registered but NOT yet invoked — the chat row
            // must wait for the outer transaction's commit to be observed.
            verify(chatService, never()).publishRuleChangeSystemMessage(
                    anyLong(), anyLong(), anyString(), anyString(), eq(false));

            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            InOrder ordering = Mockito.inOrder(ruleVersions, chatService);
            // Simulate the commit phase Spring's TransactionManager would drive.
            for (TransactionSynchronization s : syncs) {
                s.afterCommit();
            }
            ordering.verify(ruleVersions).upsertRule(
                    eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID));
            ordering.verify(chatService).publishRuleChangeSystemMessage(
                    eq(ROOM_ID), eq(3003L), eq("2026-05"), eq("DAILY_UPDATE"), eq(false));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Story 5.4 — chat publish failure is swallowed, updateRule still returns the DTO")
    void updateRule_swallowsChatPublishFailure() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(ruleVersions.upsertRule(eq(ROOM_ID), eq("2026-05"), anyString(), eq(LEADER_ID)))
                .thenReturn(1);
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(4004L, "2026-05", "DAILY_UPDATE", true)));
        doThrow(new RuntimeException("broker down"))
                .when(chatService).publishRuleChangeSystemMessage(
                        anyLong(), anyLong(), anyString(), anyString(), eq(true));

        RoomRuleVersionDto dto = service.updateRule(leader, ROOM_ID, "DAILY_UPDATE", true);

        assertThat(dto.id()).isEqualTo(4004L);
        assertThat(dto.weekendInclude()).isTrue();
        verify(chatService).publishRuleChangeSystemMessage(
                eq(ROOM_ID), eq(4004L), eq("2026-05"), eq("DAILY_UPDATE"), eq(true));
    }

    // ---------- getRule ----------

    @Test
    @DisplayName("getRule member happy — returns current + null pending when no pending row exists")
    void getRule_memberWithNoPending_returnsCurrentOnly() {
        when(rooms.existsById(ROOM_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, MEMBER_ID)).thenReturn(true);
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        ROOM_ID, "2026-04"))
                .thenReturn(Optional.of(stubRow(500L, "2026-04", "DAILY_UPDATE", true)));
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.empty());

        RoomRuleStateDto state = service.getRule(member, ROOM_ID);

        assertThat(state.current().id()).isEqualTo(500L);
        assertThat(state.current().effectiveFromMonth()).isEqualTo("2026-04");
        assertThat(state.current().weekendInclude()).isTrue();
        assertThat(state.pending()).isNull();
    }

    @Test
    @DisplayName("getRule member happy — returns current + pending when leader has staged an edit")
    void getRule_memberWithPending_returnsBoth() {
        when(rooms.existsById(ROOM_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, MEMBER_ID)).thenReturn(true);
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        ROOM_ID, "2026-04"))
                .thenReturn(Optional.of(stubRow(500L, "2026-04", "DAILY_UPDATE", true)));
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(700L, "2026-05", "DAILY_UPDATE", false)));

        RoomRuleStateDto state = service.getRule(member, ROOM_ID);

        assertThat(state.current().effectiveFromMonth()).isEqualTo("2026-04");
        assertThat(state.current().weekendInclude()).isTrue();
        assertThat(state.pending()).isNotNull();
        assertThat(state.pending().effectiveFromMonth()).isEqualTo("2026-05");
        assertThat(state.pending().weekendInclude()).isFalse();
    }

    @Test
    @DisplayName("getRule captures one clock instant so a month-boundary request stays coherent")
    void getRule_crossingMonthBoundary_usesOneInstant() {
        Clock boundaryClock = mock(Clock.class);
        when(boundaryClock.instant()).thenReturn(
                LocalDateTime.of(2026, 4, 30, 23, 59, 59).atZone(KST).toInstant(),
                LocalDateTime.of(2026, 5, 1, 0, 0, 1).atZone(KST).toInstant());
        service = build(boundaryClock);
        when(rooms.existsById(ROOM_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, MEMBER_ID)).thenReturn(true);
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        ROOM_ID, "2026-04"))
                .thenReturn(Optional.of(stubRow(500L, "2026-04", "DAILY_UPDATE", true)));
        when(ruleVersions.findByRoomIdAndEffectiveFromMonth(ROOM_ID, "2026-05"))
                .thenReturn(Optional.of(stubRow(700L, "2026-05", "DAILY_UPDATE", false)));

        RoomRuleStateDto state = service.getRule(member, ROOM_ID);

        assertThat(state.current().effectiveFromMonth()).isEqualTo("2026-04");
        assertThat(state.pending()).isNotNull();
        assertThat(state.pending().effectiveFromMonth()).isEqualTo("2026-05");
        verify(boundaryClock, times(1)).instant();
    }

    @Test
    @DisplayName("getRule non-member → ForbiddenException, no rule lookup")
    void getRule_nonMember_throwsForbidden() {
        when(rooms.existsById(ROOM_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, STRANGER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getRule(stranger, ROOM_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("방 멤버만 접근할 수 있습니다.");

        verify(ruleVersions, never())
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        anyLong(), anyString());
    }

    @Test
    @DisplayName("getRule unknown room → NotFoundException, no member check")
    void getRule_unknownRoom_throwsNotFound() {
        when(rooms.existsById(ROOM_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getRule(member, ROOM_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("방을 찾을 수 없습니다.");

        verify(roomMembers, never()).existsByRoomIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("getRule current row missing (data-shape bug) → IllegalStateException")
    void getRule_currentRuleMissing_throwsIllegalState() {
        when(rooms.existsById(ROOM_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, MEMBER_ID)).thenReturn(true);
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        ROOM_ID, "2026-04"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRule(member, ROOM_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule version missing");
    }

    // ---------- helpers ----------

    private RoomRuleVersion stubRow(long id, String month, String preset, boolean weekendInclude) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("preset", preset);
        payload.put("weekendInclude", weekendInclude);
        RoomRuleVersion row = new RoomRuleVersion(ROOM_ID, month, payload, LEADER_ID);
        setField(row, "id", id);
        setField(row, "createdAt", Instant.parse("2026-04-15T03:00:00Z"));
        return row;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
    }

    private static Room makeRoom(long id, User owner) {
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
