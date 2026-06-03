package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateTransitionEvent;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Story 5.3 AC11 row 1 — Mockito unit assertions for
 * {@link AutoLeaderPromotionListener}. Eleven cases cover the AC2 filter
 * branches, the AC4 manual-transfer race guard, the AC5 atomic flip
 * contract, the AC6 afterCommit emission, the AC7 dormant-room early
 * return, the AC2 revival no-reclaim invariant, and the
 * broker-failure swallowing in the inner publish try/catch.
 *
 * <p>The {@code @TransactionalEventListener(AFTER_COMMIT)} wiring is NOT
 * exercised at the unit layer — that's the integration test's job
 * (AC11 row 3). The listener method is invoked directly so each branch
 * stays fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoLeaderPromotionListenerTest {

    private static final long ROOM_ID = 42L;
    private static final long PREVIOUS_LEADER_ID = 1L;
    private static final long CANDIDATE_ID = 2L;
    private static final long OTHER_MEMBER_ID = 3L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-06-03T21:00:00Z");
    private static final Instant BROAD_VISIBILITY_AT = OCCURRED_AT.plus(Duration.ofHours(24));

    @Mock private RoomService roomService;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private SurvivalStateRepository survivalStates;
    @Mock private RealtimePublisher realtime;

    private AutoLeaderPromotionListener listener;
    private User previousLeader;
    private User candidate;
    private Room room;
    private RoomMember previousLeaderMember;
    private RoomMember candidateMember;

    @BeforeEach
    void setUp() {
        listener = new AutoLeaderPromotionListener(roomService, roomMembers, survivalStates, realtime);
        previousLeader = makeUser(PREVIOUS_LEADER_ID, "leader@example.com", "Leader");
        candidate = makeUser(CANDIDATE_ID, "candidate@example.com", "Candidate");
        room = makeRoom(ROOM_ID, previousLeader);
        previousLeaderMember = new RoomMember(room, previousLeader, RoomRole.OWNER);
        candidateMember = new RoomMember(room, candidate, RoomRole.MEMBER);
        SurvivalState activeState = stateWithStatus(SurvivalStatus.ACTIVE);
        when(roomMembers.findByRoomIdAndUserIdForUpdate(ROOM_ID, CANDIDATE_ID))
                .thenReturn(Optional.of(candidateMember));
        when(survivalStates.findByRoomIdAndUserIdForUpdate(ROOM_ID, CANDIDATE_ID))
                .thenReturn(Optional.of(activeState));
    }

    @Test
    @DisplayName("skip on toStatus=YELLOW (AC2 — only RED transitions trigger)")
    void filter_yellow_noOp() {
        listener.onTransition(transition(
                SurvivalStatus.ACTIVE, SurvivalStatus.YELLOW,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        verifyNoInteractions(roomService, roomMembers, realtime);
    }

    @Test
    @DisplayName("skip on toStatus=ACTIVE — revival path never reclaims leadership (AC2)")
    void filter_active_revivedFormerLeaderDoesNotReclaim() {
        // Synthesize the event RevivalService publishes when the previously
        // eliminated leader self-revives — toStatus=ACTIVE, userId is the
        // former leader, ownerUserId still references them as a stale snapshot
        // (auto-promotion has not yet rewritten owner_id). The AC2 filter
        // MUST reject this so the previous leader cannot auto-reclaim.
        listener.onTransition(transition(
                SurvivalStatus.RED, SurvivalStatus.ACTIVE,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        verifyNoInteractions(roomService, roomMembers, realtime);
    }

    @Test
    @DisplayName("skip on toStatus=RED but userId != ownerUserId (non-leader elimination — AC2)")
    void filter_nonLeaderRed_noOp() {
        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                OTHER_MEMBER_ID, PREVIOUS_LEADER_ID));

        verifyNoInteractions(roomService, roomMembers, realtime);
    }

    @Test
    @DisplayName("skip on ownerUserId=null (defensive, AC2)")
    void filter_nullOwnerUserId_noOp() {
        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, null));

        verifyNoInteractions(roomService, roomMembers, realtime);
    }

    @Test
    @DisplayName("AC4 manual-transfer race — current owner differs from event leader → no-op")
    void raceGuard_ownerAlreadyChanged_noOp() {
        // Manual transfer committed first, rewriting rooms.owner_id to a
        // different user. The listener fires with event.userId = old leader,
        // but the row-locked re-read shows the new owner — the AC4 guard
        // short-circuits and the manual choice is respected.
        User otherOwner = makeUser(OTHER_MEMBER_ID, "other@example.com", "Other");
        room.setOwner(otherOwner);
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);

        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        verify(roomService).requireRoomForUpdate(ROOM_ID);
        verifyNoInteractions(roomMembers, realtime);
        assertThat(room.getOwner().getId()).isEqualTo(OTHER_MEMBER_ID);
    }

    @Test
    @DisplayName("happy path: ACTIVE candidate found → owner flips + both roles flip + publish")
    void happyPath_flipsAllThreeRowsAndPublishes() {
        User laterCandidate = makeUser(OTHER_MEMBER_ID, "other@example.com", "Other");
        RoomMember laterCandidateMember = new RoomMember(room, laterCandidate, RoomRole.MEMBER);
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of(candidateMember, laterCandidateMember));
        when(roomMembers.findByRoomAndUser(room, previousLeader))
                .thenReturn(Optional.of(previousLeaderMember));

        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        assertThat(room.getOwner().getId()).isEqualTo(CANDIDATE_ID);
        assertThat(candidateMember.getRole()).isEqualTo(RoomRole.OWNER);
        assertThat(laterCandidateMember.getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(previousLeaderMember.getRole()).isEqualTo(RoomRole.MEMBER);

        ArgumentCaptor<LeadershipChangePayload> payloadCaptor =
                ArgumentCaptor.forClass(LeadershipChangePayload.class);
        verify(realtime).publishLeadershipChange(eq(ROOM_ID), payloadCaptor.capture());
        LeadershipChangePayload payload = payloadCaptor.getValue();
        assertThat(payload.roomId()).isEqualTo(ROOM_ID);
        assertThat(payload.previousLeaderUserId()).isEqualTo(PREVIOUS_LEADER_ID);
        assertThat(payload.newLeaderUserId()).isEqualTo(CANDIDATE_ID);
        assertThat(payload.reason()).isEqualTo("AUTO_ELIMINATION");
    }

    @Test
    @DisplayName("AC11 Trap #11 — load previous-leader membership BEFORE setOwner")
    void happyPath_loadsPreviousMembershipBeforeSetOwner() {
        // If the listener called setOwner BEFORE findByRoomAndUser(room,
        // room.getOwner()), the membership lookup would resolve to the NEW
        // leader's row and double-flip it to MEMBER. Verify the previous-
        // leader's membership is loaded with the OLD owner reference.
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of(candidateMember));
        when(roomMembers.findByRoomAndUser(room, previousLeader))
                .thenReturn(Optional.of(previousLeaderMember));

        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(roomMembers).findByRoomAndUser(eq(room), userCaptor.capture());
        assertThat(userCaptor.getValue().getId()).isEqualTo(PREVIOUS_LEADER_ID);
    }

    @Test
    @DisplayName("AC7 dormant: no ACTIVE candidates → no writes, no emission")
    void dormant_noCandidates_noWritesNoPublish() {
        Logger logger = (Logger) LoggerFactory.getLogger(AutoLeaderPromotionListener.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        logger.addAppender(logCapture);
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of());

        try {
            listener.onTransition(transition(
                    SurvivalStatus.YELLOW, SurvivalStatus.RED,
                    PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));
        } finally {
            logger.detachAppender(logCapture);
        }

        assertThat(room.getOwner().getId()).isEqualTo(PREVIOUS_LEADER_ID);
        assertThat(previousLeaderMember.getRole()).isEqualTo(RoomRole.OWNER);
        verify(roomMembers, never()).findByRoomAndUser(any(Room.class), any(User.class));
        verifyNoInteractions(realtime);
        assertThat(logCapture.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("[auto-leader] dormant roomId=42 previousLeaderUserId=1"));
    }

    @Test
    @DisplayName("candidate revalidation skips when locked survival row is no longer ACTIVE")
    void candidateNoLongerActive_noWritesNoPublish() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of(candidateMember));
        when(roomMembers.findByRoomAndUser(room, previousLeader))
                .thenReturn(Optional.of(previousLeaderMember));
        SurvivalState yellowState = stateWithStatus(SurvivalStatus.YELLOW);
        when(survivalStates.findByRoomIdAndUserIdForUpdate(ROOM_ID, CANDIDATE_ID))
                .thenReturn(Optional.of(yellowState));

        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        assertThat(room.getOwner().getId()).isEqualTo(PREVIOUS_LEADER_ID);
        assertThat(candidateMember.getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(previousLeaderMember.getRole()).isEqualTo(RoomRole.OWNER);
        verifyNoInteractions(realtime);
    }

    @Test
    @DisplayName("repository receives the previous-leader user_id as the exclusion key")
    void delegatesExcludedUserIdToRepository() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(anyLong(), anyLong()))
                .thenReturn(List.of());

        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        verify(roomMembers).findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID);
    }

    @Test
    @DisplayName("publish runs immediately when no tx synchronization is active")
    void publishCalledOnce_noTxSynchronization() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of(candidateMember));
        when(roomMembers.findByRoomAndUser(room, previousLeader))
                .thenReturn(Optional.of(previousLeaderMember));

        listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

        verify(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));
    }

    @Test
    @DisplayName("publish is deferred until afterCommit when tx synchronization is active")
    void publishDeferredUntilAfterCommit() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of(candidateMember));
        when(roomMembers.findByRoomAndUser(room, previousLeader))
                .thenReturn(Optional.of(previousLeaderMember));

        TransactionSynchronizationManager.initSynchronization();
        try {
            listener.onTransition(transition(
                    SurvivalStatus.YELLOW, SurvivalStatus.RED,
                    PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID));

            verifyNoInteractions(realtime);
            for (TransactionSynchronization sync
                    : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));
    }

    @Test
    @DisplayName("broker RuntimeException is swallowed by inner try/catch (does not escape)")
    void publishBrokerFailureSwallowed() {
        when(roomService.requireRoomForUpdate(ROOM_ID)).thenReturn(room);
        when(roomMembers.findLongestTenuredActiveCandidates(ROOM_ID, PREVIOUS_LEADER_ID))
                .thenReturn(List.of(candidateMember));
        when(roomMembers.findByRoomAndUser(room, previousLeader))
                .thenReturn(Optional.of(previousLeaderMember));
        doThrow(new RuntimeException("simulated broker hiccup"))
                .when(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));

        assertThatCode(() -> listener.onTransition(transition(
                SurvivalStatus.YELLOW, SurvivalStatus.RED,
                PREVIOUS_LEADER_ID, PREVIOUS_LEADER_ID)))
                .doesNotThrowAnyException();

        assertThat(room.getOwner().getId()).isEqualTo(CANDIDATE_ID);
        verify(realtime).publishLeadershipChange(eq(ROOM_ID), any(LeadershipChangePayload.class));
    }

    // ----- helpers -----

    private SurvivalStateTransitionEvent transition(
            SurvivalStatus from, SurvivalStatus to, long userId, Long ownerUserId) {
        return new SurvivalStateTransitionEvent(
                ROOM_ID, userId, ownerUserId, from, to,
                OCCURRED_AT,
                to == SurvivalStatus.RED ? BROAD_VISIBILITY_AT : null);
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

    private static SurvivalState stateWithStatus(SurvivalStatus status) {
        SurvivalState state = mock(SurvivalState.class);
        when(state.getStatus()).thenReturn(status);
        return state;
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
