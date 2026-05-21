package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateTransitionEvent;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
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

/**
 * Story 3.2 AC12 — eligible-giver push fan-out verification.
 *
 * <ol>
 *   <li>RED transition with 3 eligible givers → 3 push calls, identical
 *       elimination-keyed dedup key.</li>
 *   <li>RED transition with 0 eligible givers → 0 push calls.</li>
 *   <li>SPECTATOR transition → 0 push calls (RED-only filter).</li>
 *   <li>Push provider throws → other givers still get their push.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EligibleGiverPushListenerTest {

    private static final long ROOM_ID = 42L;
    private static final long RECEIVER_ID = 11L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-18T03:14:15Z");
    private static final String EXPECTED_KEY =
            ROOM_ID + ":" + RECEIVER_ID + ":" + OCCURRED_AT.toEpochMilli();

    @Mock private FriendGiftEligibilityQuery eligibilityQuery;
    @Mock private UserRepository users;
    @Mock private SurvivalStateRepository survivalStates;
    @Mock private NotificationService notificationService;

    private EligibleGiverPushListener listener;
    private User receiver;

    @BeforeEach
    void setUp() {
        listener = new EligibleGiverPushListener(
                eligibilityQuery, users, survivalStates, notificationService);
        receiver = makeUser(RECEIVER_ID, "receiver@example.com", "수진");
        when(users.findById(RECEIVER_ID)).thenReturn(Optional.of(receiver));
    }

    @Test
    @DisplayName("RED transition with 3 eligible givers → 3 push calls with shared dedup key")
    void onTransition_threeEligibleGivers_pushesThree() {
        User g1 = makeUser(1L, "g1@example.com", "G1");
        User g2 = makeUser(2L, "g2@example.com", "G2");
        User g3 = makeUser(3L, "g3@example.com", "G3");
        when(eligibilityQuery.findEligibleGiverUserIds(ROOM_ID, RECEIVER_ID))
                .thenReturn(List.of(1L, 2L, 3L));
        when(users.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(g1, g2, g3));

        listener.onTransition(redTransition());

        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(3)).sendEvent(
                userCap.capture(),
                eq(NotificationKind.FRIEND_GIFT_PROMPT),
                keyCap.capture(),
                any(String.class),
                any(String.class),
                eq(Duration.ZERO));

        assertThat(userCap.getAllValues()).containsExactly(g1, g2, g3);
        assertThat(keyCap.getAllValues()).allMatch(k -> k.equals(EXPECTED_KEY));
    }

    @Test
    @DisplayName("RED transition with 0 eligible givers → 0 push calls")
    void onTransition_zeroEligibleGivers_pushesZero() {
        when(eligibilityQuery.findEligibleGiverUserIds(ROOM_ID, RECEIVER_ID))
                .thenReturn(List.of());

        listener.onTransition(redTransition());

        verify(notificationService, never()).sendEvent(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SPECTATOR transition → 0 push calls (filtered out)")
    void onTransition_spectatorTransition_skipsFanOut() {
        listener.onTransition(spectatorTransition());

        verify(eligibilityQuery, never()).findEligibleGiverUserIds(anyLong(), anyLong());
        verify(notificationService, never()).sendEvent(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("push provider throws for one giver → other givers still get their push")
    void onTransition_oneFailure_doesNotPoisonFanOut() {
        User g1 = makeUser(1L, "g1@example.com", "G1");
        User g2 = makeUser(2L, "g2@example.com", "G2");
        when(eligibilityQuery.findEligibleGiverUserIds(ROOM_ID, RECEIVER_ID))
                .thenReturn(List.of(1L, 2L));
        when(users.findAllById(List.of(1L, 2L))).thenReturn(List.of(g1, g2));
        org.mockito.Mockito.doThrow(new RuntimeException("transient push provider error"))
                .doNothing()
                .when(notificationService)
                .sendEvent(any(), any(), any(), any(), any(), any());

        listener.onTransition(redTransition());

        verify(notificationService, times(2)).sendEvent(
                any(), eq(NotificationKind.FRIEND_GIFT_PROMPT),
                any(), any(), any(), eq(Duration.ZERO));
    }

    // ----- helpers -----

    private SurvivalStateTransitionEvent redTransition() {
        return new SurvivalStateTransitionEvent(
                ROOM_ID, RECEIVER_ID, /* ownerUserId */ 99L,
                SurvivalStatus.ACTIVE, SurvivalStatus.RED,
                OCCURRED_AT, OCCURRED_AT.plus(Duration.ofHours(24)));
    }

    private SurvivalStateTransitionEvent spectatorTransition() {
        return new SurvivalStateTransitionEvent(
                ROOM_ID, RECEIVER_ID, /* ownerUserId */ 99L,
                SurvivalStatus.RED, SurvivalStatus.SPECTATOR,
                OCCURRED_AT, null);
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return u;
    }
}
