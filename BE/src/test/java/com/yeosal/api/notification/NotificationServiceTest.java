package com.yeosal.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationPrefRepository prefs;
    @Mock private PushTokenRepository pushTokens;
    @Mock private NotificationLogRepository logs;
    @Mock private ExpoPushClient pushClient;

    private final QuietHoursPolicy quietHours = new QuietHoursPolicy();
    // 2026-04-30 11:00 UTC == 20:00 KST → not in quiet window 22-08
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-30T11:00:00Z"), ZoneId.of("Asia/Seoul"));

    private NotificationService service;
    private User alice;

    @BeforeEach
    void setUp() {
        service = new NotificationService(prefs, pushTokens, logs, pushClient, quietHours, clock);
        alice = makeUser(1L, "alice@example.com", "Alice");
    }

    @Test
    @DisplayName("sendCron: happy path dispatches and writes log row")
    void sendCronHappyPath() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.GOAL_NUDGE, "2026-04-30")).thenReturn(false);
        when(pushTokens.findByUser(alice)).thenReturn(List.of(
                new PushToken(alice, "ExponentPushToken[abc]", "ios")
        ));
        when(pushClient.send(anyList(), anyString(), anyString())).thenReturn(true);

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "오늘의 목표", "정해보세요");

        verify(pushClient).send(List.of("ExponentPushToken[abc]"), "오늘의 목표", "정해보세요");
        verify(logs).save(any(NotificationLog.class));
    }

    @Test
    @DisplayName("sendCron: skips dedup-log write when push send fails")
    void sendCronSendFailureSkipsLog() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.GOAL_NUDGE, "2026-04-30")).thenReturn(false);
        when(pushTokens.findByUser(alice)).thenReturn(List.of(
                new PushToken(alice, "ExponentPushToken[abc]", "ios")
        ));
        when(pushClient.send(anyList(), anyString(), anyString())).thenReturn(false);

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient).send(List.of("ExponentPushToken[abc]"), "t", "b");
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendCron: skips when dedup row already exists")
    void sendCronDedupSkips() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.GOAL_NUDGE, "2026-04-30")).thenReturn(true);

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendCron: skips when goal_nudge_enabled is false")
    void sendCronRespectsPrefDisabled() {
        NotificationPref pref = prefWithDefaults(alice);
        pref.setGoalNudgeEnabled(false);
        when(prefs.findById(1L)).thenReturn(Optional.of(pref));

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendCron: skips during quiet hours")
    void sendCronQuietHoursSkips() {
        // 14:00 UTC == 23:00 KST → inside default quiet window (22-08)
        Clock quiet = Clock.fixed(Instant.parse("2026-04-30T14:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new NotificationService(prefs, pushTokens, logs, pushClient, quietHours, quiet);
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendCron: skips when no push tokens registered")
    void sendCronNoTokensSkips() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.GOAL_NUDGE, "2026-04-30")).thenReturn(false);
        when(pushTokens.findByUser(alice)).thenReturn(List.of());

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendEvent: dispatches when last send was more than debounce ago")
    void sendEventOutsideDebounceDispatches() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        NotificationLog stale = new NotificationLog(alice, NotificationKind.FRIEND_GOAL, "FRIEND_GOAL:42");
        setField(stale, "sentAt", clock.instant().minus(Duration.ofMinutes(31)));
        when(logs.findLatest(alice, NotificationKind.FRIEND_GOAL)).thenReturn(Optional.of(stale));
        when(pushTokens.findByUser(alice)).thenReturn(List.of(
                new PushToken(alice, "ExponentPushToken[abc]", "ios")
        ));
        when(pushClient.send(anyList(), anyString(), anyString())).thenReturn(true);

        service.sendEvent(alice, NotificationKind.FRIEND_GOAL, "FRIEND_GOAL:42", "친구 알림", "본문",
                Duration.ofMinutes(30));

        verify(pushClient).send(List.of("ExponentPushToken[abc]"), "친구 알림", "본문");
        verify(logs).save(any(NotificationLog.class));
    }

    @Test
    @DisplayName("sendEvent: respects 30-min debounce")
    void sendEventInsideDebounceSkips() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        NotificationLog recent = new NotificationLog(alice, NotificationKind.FRIEND_GOAL, "FRIEND_GOAL:42");
        setField(recent, "sentAt", clock.instant().minus(Duration.ofMinutes(15)));
        when(logs.findLatest(alice, NotificationKind.FRIEND_GOAL)).thenReturn(Optional.of(recent));

        service.sendEvent(alice, NotificationKind.FRIEND_GOAL, "FRIEND_GOAL:42", "t", "b",
                Duration.ofMinutes(30));

        verify(pushClient, never()).send(anyList(), anyString(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendEvent: respects event_hooks_enabled=false")
    void sendEventRespectsPrefDisabled() {
        NotificationPref pref = prefWithDefaults(alice);
        pref.setEventHooksEnabled(false);
        when(prefs.findById(1L)).thenReturn(Optional.of(pref));

        service.sendEvent(alice, NotificationKind.FRIEND_GOAL, "FRIEND_GOAL:42", "t", "b",
                Duration.ofMinutes(30));

        verify(pushClient, never()).send(anyList(), anyString(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreatePref: lazily inserts default row when missing")
    void getOrCreatePrefInsertsDefault() {
        when(prefs.findById(1L)).thenReturn(Optional.empty());
        lenient().when(prefs.save(any(NotificationPref.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationPref result = service.getOrCreatePref(alice);

        assertThat(result.getQuietStartHour()).isEqualTo((short) 22);
        assertThat(result.getQuietEndHour()).isEqualTo((short) 8);
        assertThat(result.isGoalNudgeEnabled()).isTrue();
        verify(prefs).save(any(NotificationPref.class));
    }

    // -- helpers --
    private static NotificationPref prefWithDefaults(User user) {
        return new NotificationPref(user);
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

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
