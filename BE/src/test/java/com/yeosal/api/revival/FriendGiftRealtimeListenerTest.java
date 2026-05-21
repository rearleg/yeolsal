package com.yeosal.api.revival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Story 3.2 AC12 — receiver donor-confirmation push verification.
 *
 * <ol>
 *   <li>Event fires → exactly one push to the receiver, dedup key
 *       {@code "revival:{revivalEventId}"}, title includes giver nickname.</li>
 *   <li>Missing giver/receiver user row → push skipped, no exception.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FriendGiftRealtimeListenerTest {

    private static final long ROOM_ID = 42L;
    private static final long GIVER_ID = 7L;
    private static final long RECEIVER_ID = 11L;
    private static final long REVIVAL_EVENT_ID = 9002L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-18T03:14:15Z");

    @Mock private NotificationService notificationService;
    @Mock private UserRepository users;

    private FriendGiftRealtimeListener listener;

    @BeforeEach
    void setUp() {
        listener = new FriendGiftRealtimeListener(notificationService, users);
    }

    @Test
    @DisplayName("event fires → sendEvent called once with FRIEND_GIFT_RECEIVED + key='revival:{id}'")
    void onSent_happy_sendsOnePush() {
        User giver = makeUser(GIVER_ID, "giver@example.com", "정민");
        User receiver = makeUser(RECEIVER_ID, "receiver@example.com", "Receiver");
        when(users.findById(GIVER_ID)).thenReturn(Optional.of(giver));
        when(users.findById(RECEIVER_ID)).thenReturn(Optional.of(receiver));

        listener.onSent(new FriendGiftSentEvent(
                ROOM_ID, GIVER_ID, RECEIVER_ID, REVIVAL_EVENT_ID, OCCURRED_AT));

        verify(notificationService).sendEvent(
                eq(receiver),
                eq(NotificationKind.FRIEND_GIFT_RECEIVED),
                eq("revival:" + REVIVAL_EVENT_ID),
                eq("정민가 너의 회생권을 선물했어"),
                any(String.class),
                eq(Duration.ZERO));
    }

    @Test
    @DisplayName("missing user row → push skipped, no exception")
    void onSent_missingUser_skipsPush() {
        when(users.findById(GIVER_ID)).thenReturn(Optional.empty());
        when(users.findById(RECEIVER_ID)).thenReturn(Optional.empty());

        listener.onSent(new FriendGiftSentEvent(
                ROOM_ID, GIVER_ID, RECEIVER_ID, REVIVAL_EVENT_ID, OCCURRED_AT));

        verify(notificationService, never()).sendEvent(
                any(), any(), any(), any(), any(), any());
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
