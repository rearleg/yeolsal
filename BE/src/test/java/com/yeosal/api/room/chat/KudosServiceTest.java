package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Mockito unit tests for {@link KudosService} (Story 3.5 AC11).
 *
 * <p>Covers the AC3 sequence variants: happy path with / without
 * message, dedup race, DIVE translation (with matching + non-matching
 * constraint name), the eligibility/friendship gates, boundary
 * conditions on the message length, and the defensive missing-target
 * survival_state row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KudosServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-17T03:14:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
    private static final LocalDate KST_TODAY =
            LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));

    private static final long ROOM_ID = 42L;
    private static final long SENDER_ID = 7L;
    private static final long TARGET_ID = 11L;
    private static final long KUDOS_ID = 9001L;
    private static final String DEDUP_CONSTRAINT = "ux_kudos_one_per_day";

    @Mock private ChatMessageRepository messages;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private SurvivalStateRepository survivalStates;
    @Mock private FriendshipRepository friendships;
    @Mock private UserRepository users;
    @Mock private ApplicationEventPublisher eventPublisher;

    private KudosService service;
    private User sender;
    private User target;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new KudosService(
                messages, roomMembers, survivalStates, friendships, users,
                eventPublisher, objectMapper, CLOCK);
        sender = makeUser(SENDER_ID, "alice@example.com", "alice");
        target = makeUser(TARGET_ID, "bob@example.com", "bob");

        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, SENDER_ID)).thenReturn(true);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, TARGET_ID)).thenReturn(true);
        when(users.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, SENDER_ID))
                .thenReturn(Optional.of(stateWithStatus(SurvivalStatus.ACTIVE)));
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(stateWithStatus(SurvivalStatus.RED)));
        when(friendships.findBetween(sender, target))
                .thenReturn(Optional.of(friendship(FriendshipStatus.ACCEPTED)));
        when(messages.insertKudosIfAbsent(eq(ROOM_ID), eq(SENDER_ID), anyString(), anyString()))
                .thenReturn(1);
        when(messages.findKudosId(SENDER_ID, String.valueOf(TARGET_ID), KST_TODAY))
                .thenReturn(Optional.of(KUDOS_ID));
    }

    @Test
    @DisplayName("happy — null message → DTO, event published, message stored as empty string")
    void sendKudos_happy_nullMessage() {
        KudosDto dto = service.sendKudos(ROOM_ID, sender, TARGET_ID, null);

        assertThat(dto.kudosId()).isEqualTo(KUDOS_ID);
        assertThat(dto.roomId()).isEqualTo(ROOM_ID);
        assertThat(dto.senderUserId()).isEqualTo(SENDER_ID);
        assertThat(dto.targetUserId()).isEqualTo(TARGET_ID);
        assertThat(dto.message()).isEmpty();
        assertThat(dto.occurredAt()).isEqualTo(NOW);

        ArgumentCaptor<KudosSentEvent> captor = ArgumentCaptor.forClass(KudosSentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().senderUserId()).isEqualTo(SENDER_ID);
        assertThat(captor.getValue().targetUserId()).isEqualTo(TARGET_ID);
        assertThat(captor.getValue().messagePreview()).contains("alice");
    }

    @Test
    @DisplayName("happy — message present → payload carries trimmed message + DTO returns it")
    void sendKudos_happy_withMessage() {
        KudosDto dto = service.sendKudos(ROOM_ID, sender, TARGET_ID, "  우리 같이 가자  ");

        assertThat(dto.message()).isEqualTo("우리 같이 가자");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messages).insertKudosIfAbsent(
                eq(ROOM_ID), eq(SENDER_ID), anyString(), payloadCaptor.capture());
        // Persisted payload carries the trimmed message verbatim.
        assertThat(payloadCaptor.getValue()).contains("\"message\":\"우리 같이 가자\"");
        // Both ids are stored as JSON strings — V8/V9 convention.
        assertThat(payloadCaptor.getValue()).contains("\"senderUserId\":\"" + SENDER_ID + "\"");
        assertThat(payloadCaptor.getValue()).contains("\"targetUserId\":\"" + TARGET_ID + "\"");
    }

    @Test
    @DisplayName("same-day duplicate — insertKudosIfAbsent returns 0 → KudosAlreadySentTodayException")
    void sendKudos_duplicate_throws() {
        when(messages.insertKudosIfAbsent(eq(ROOM_ID), eq(SENDER_ID), anyString(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(KudosAlreadySentTodayException.class);
        verify(eventPublisher, never()).publishEvent(any(KudosSentEvent.class));
    }

    @Test
    @DisplayName("DIVE with kudos constraint name → translated to KudosAlreadySentTodayException")
    void sendKudos_diveWithKudosConstraint_translates() {
        when(messages.insertKudosIfAbsent(eq(ROOM_ID), eq(SENDER_ID), anyString(), anyString()))
                .thenThrow(makeDiveForConstraint(DEDUP_CONSTRAINT));

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(KudosAlreadySentTodayException.class);
    }

    @Test
    @DisplayName("DIVE with a different constraint → rethrown (preserves dataIntegrity 500 path)")
    void sendKudos_diveWithOtherConstraint_rethrows() {
        DataIntegrityViolationException ex = makeDiveForConstraint("fk_room_id_other");
        when(messages.insertKudosIfAbsent(eq(ROOM_ID), eq(SENDER_ID), anyString(), anyString()))
                .thenThrow(ex);

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isSameAs(ex);
    }

    @Test
    @DisplayName("sender is SPECTATOR → SpectatorWriteForbiddenException")
    void sendKudos_senderSpectator_throws() {
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, SENDER_ID))
                .thenReturn(Optional.of(stateWithStatus(SurvivalStatus.SPECTATOR)));

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(SpectatorWriteForbiddenException.class);
    }

    @Test
    @DisplayName("target is ACTIVE → KudosTargetNotEligibleException")
    void sendKudos_targetActive_throws() {
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(stateWithStatus(SurvivalStatus.ACTIVE)));

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(KudosTargetNotEligibleException.class);
    }

    @Test
    @DisplayName("target is YELLOW → KudosTargetNotEligibleException (eligible set is {RED, SPECTATOR})")
    void sendKudos_targetYellow_throws() {
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.of(stateWithStatus(SurvivalStatus.YELLOW)));

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(KudosTargetNotEligibleException.class);
    }

    @Test
    @DisplayName("target survival_state row missing → KudosTargetNotEligibleException (defensive)")
    void sendKudos_targetMissingState_throws() {
        when(survivalStates.findByRoomIdAndUserId(ROOM_ID, TARGET_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(KudosTargetNotEligibleException.class);
    }

    @Test
    @DisplayName("no friendship row → NotFriendsException")
    void sendKudos_noFriendship_throws() {
        when(friendships.findBetween(sender, target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(NotFriendsException.class);
    }

    @Test
    @DisplayName("friendship PENDING → NotFriendsException (must be ACCEPTED)")
    void sendKudos_friendshipPending_throws() {
        when(friendships.findBetween(sender, target))
                .thenReturn(Optional.of(friendship(FriendshipStatus.PENDING)));

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(NotFriendsException.class);
    }

    @Test
    @DisplayName("friendship BLOCKED → NotFriendsException")
    void sendKudos_friendshipBlocked_throws() {
        when(friendships.findBetween(sender, target))
                .thenReturn(Optional.of(friendship(FriendshipStatus.BLOCKED)));

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(NotFriendsException.class);
    }

    @Test
    @DisplayName("self-kudos (sender == target) → BadRequestException")
    void sendKudos_selfTarget_throws() {
        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, SENDER_ID, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("non-member sender → ForbiddenException")
    void sendKudos_nonMemberSender_throws() {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, SENDER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("non-member target → NotFoundException")
    void sendKudos_nonMemberTarget_throws() {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, TARGET_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("message exactly 60 chars → succeeds (boundary)")
    void sendKudos_messageExactly60_succeeds() {
        String msg = "0".repeat(60);

        KudosDto dto = service.sendKudos(ROOM_ID, sender, TARGET_ID, msg);

        assertThat(dto.message()).hasSize(60);
        verify(messages, times(1)).insertKudosIfAbsent(
                eq(ROOM_ID), eq(SENDER_ID), anyString(), anyString());
    }

    @Test
    @DisplayName("message 61 chars → BadRequestException (service-layer storage guard)")
    void sendKudos_message61_throws() {
        String msg = "0".repeat(61);

        assertThatThrownBy(() -> service.sendKudos(ROOM_ID, sender, TARGET_ID, msg))
                .isInstanceOf(BadRequestException.class);
        verify(messages, never()).insertKudosIfAbsent(anyLong(), anyLong(), anyString(), anyString());
    }

    // ----- helpers -----

    /**
     * Builds a {@link DataIntegrityViolationException} whose most-specific
     * cause is a plain {@link SQLException} that names {@code constraint}
     * in its message. {@code KudosService.isKudosDedupConflict} only
     * inspects the cause message string, so this is sufficient to
     * exercise the discriminator without depending on the PostgreSQL
     * JDBC driver internals.
     */
    private static DataIntegrityViolationException makeDiveForConstraint(String constraint) {
        SQLException cause = new SQLException(
                "duplicate key value violates unique constraint \"" + constraint + "\"",
                "23505");
        return new DataIntegrityViolationException("duplicate key", cause);
    }

    private SurvivalState stateWithStatus(SurvivalStatus status) {
        SurvivalState state = new SurvivalState();
        setField(state, "status", status);
        setField(state, "lastStateChangeAt", NOW);
        if (status == SurvivalStatus.RED || status == SurvivalStatus.SPECTATOR) {
            setField(state, "eliminatedAt", NOW);
        }
        return state;
    }

    private Friendship friendship(FriendshipStatus status) {
        Friendship f = new Friendship(sender, target);
        f.setStatus(status);
        return f;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
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
