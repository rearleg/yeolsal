package com.yeosal.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(pushClient.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(true);

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "오늘의 목표", "정해보세요");

        verify(pushClient).send(
                eq(List.of("ExponentPushToken[abc]")), eq("오늘의 목표"), eq("정해보세요"), anyMap());
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
        when(pushClient.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(false);

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient).send(
                eq(List.of("ExponentPushToken[abc]")), eq("t"), eq("b"), anyMap());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendCron: skips when dedup row already exists")
    void sendCronDedupSkips() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.GOAL_NUDGE, "2026-04-30")).thenReturn(true);

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("sendCron: skips when goal_nudge_enabled is false")
    void sendCronRespectsPrefDisabled() {
        NotificationPref pref = prefWithDefaults(alice);
        pref.setGoalNudgeEnabled(false);
        when(prefs.findById(1L)).thenReturn(Optional.of(pref));

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
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

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("sendCron: skips when no push tokens registered")
    void sendCronNoTokensSkips() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.GOAL_NUDGE, "2026-04-30")).thenReturn(false);
        when(pushTokens.findByUser(alice)).thenReturn(List.of());

        service.sendCron(alice, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
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
        when(pushClient.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(true);

        service.sendEvent(alice, NotificationKind.FRIEND_GOAL, "FRIEND_GOAL:42", "친구 알림", "본문",
                Duration.ofMinutes(30));

        verify(pushClient).send(
                eq(List.of("ExponentPushToken[abc]")), eq("친구 알림"), eq("본문"), anyMap());
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

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
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

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreatePref: native upsert path returns the row the upsert wrote")
    void getOrCreatePrefInsertsDefault() {
        NotificationPref winner = new NotificationPref(alice);
        when(prefs.findById(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        NotificationPref result = service.getOrCreatePref(alice);

        assertThat(result).isSameAs(winner);
        verify(prefs).insertDefaultIfAbsent(1L);
    }

    @Test
    @DisplayName("getOrCreatePref: dup race resolves through Postgres — re-read returns the peer's row")
    void getOrCreatePrefRecoversAfterDupRace() {
        NotificationPref winner = new NotificationPref(alice);
        // The peer's insert already committed. Our insertDefaultIfAbsent is a no-op
        // (ON CONFLICT DO NOTHING), and the second findById returns the peer's row.
        when(prefs.findById(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        NotificationPref result = service.getOrCreatePref(alice);

        assertThat(result).isSameAs(winner);
        verify(prefs).insertDefaultIfAbsent(1L);
    }

    @Test
    @DisplayName("getOrCreatePref: re-read miss after upsert surfaces an explicit IllegalStateException")
    void getOrCreatePrefRereadMissThrows() {
        when(prefs.findById(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreatePref(alice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user_id=1");
    }

    @Test
    @DisplayName("sendCron: SPECTATOR_DIGEST routes through event_hooks_enabled — enabled → push fires")
    void sendCronSpectatorDigestRespectsEventHooksEnabled() {
        when(prefs.findById(1L)).thenReturn(Optional.of(prefWithDefaults(alice)));
        when(logs.existsByUserAndKindAndKey(alice, NotificationKind.SPECTATOR_DIGEST, "2026-04-29:1:42"))
                .thenReturn(false);
        when(pushTokens.findByUser(alice)).thenReturn(List.of(
                new PushToken(alice, "ExponentPushToken[abc]", "ios")
        ));
        when(pushClient.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(true);

        service.sendCron(alice, NotificationKind.SPECTATOR_DIGEST, "2026-04-29:1:42",
                "오늘도 우리 방이 함께 살아남고 있어요", "어제 메시지 12개 · 새 글 3개");

        verify(pushClient).send(
                eq(List.of("ExponentPushToken[abc]")),
                eq("오늘도 우리 방이 함께 살아남고 있어요"),
                eq("어제 메시지 12개 · 새 글 3개"),
                anyMap());
        verify(logs).save(any(NotificationLog.class));
    }

    @Test
    @DisplayName("sendCron: SPECTATOR_DIGEST skipped when event_hooks_enabled=false")
    void sendCronSpectatorDigestRespectsEventHooksDisabled() {
        NotificationPref pref = prefWithDefaults(alice);
        pref.setEventHooksEnabled(false);
        when(prefs.findById(1L)).thenReturn(Optional.of(pref));

        service.sendCron(alice, NotificationKind.SPECTATOR_DIGEST, "2026-04-29:1:42", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
        verify(logs, never()).save(any());
    }

    @Test
    @DisplayName("isInQuietHours fallback: blank user timezone is treated as Asia/Seoul")
    void quietHoursFallbackForBlankTimezone() {
        // 14:00 UTC == 23:00 KST → inside default quiet window 22-08, so we should skip.
        Clock quiet = Clock.fixed(Instant.parse("2026-04-30T14:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new NotificationService(prefs, pushTokens, logs, pushClient, quietHours, quiet);
        User noTz = makeUserWithTimezone(2L, "bob@example.com", "Bob", "");
        when(prefs.findById(2L)).thenReturn(Optional.of(prefWithDefaults(noTz)));

        service.sendCron(noTz, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("isInQuietHours fallback: invalid IANA id falls back to Asia/Seoul without throwing")
    void quietHoursFallbackForInvalidTimezone() {
        Clock quiet = Clock.fixed(Instant.parse("2026-04-30T14:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new NotificationService(prefs, pushTokens, logs, pushClient, quietHours, quiet);
        User badTz = makeUserWithTimezone(3L, "carol@example.com", "Carol", "Not/A_Real_Zone");
        when(prefs.findById(3L)).thenReturn(Optional.of(prefWithDefaults(badTz)));

        // No exception bubbles out; quiet-hour check resolves and skip path runs.
        service.sendCron(badTz, NotificationKind.GOAL_NUDGE, "2026-04-30", "t", "b");

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
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

    private static User makeUserWithTimezone(long id, String email, String nickname, String timezone) {
        User u = makeUser(id, email, nickname);
        setField(u, "timezone", timezone);
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
