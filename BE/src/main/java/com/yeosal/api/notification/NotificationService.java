package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates push delivery for cron nudges and event hooks. Three gates are
 * applied before any send: per-user pref toggles, quiet hours (in the user's
 * timezone), and idempotency / debounce against the {@code notification_log}.
 */
@Service
public class NotificationService {

    private final NotificationPrefRepository prefs;
    private final PushTokenRepository pushTokens;
    private final NotificationLogRepository logs;
    private final ExpoPushClient pushClient;
    private final QuietHoursPolicy quietHours;
    private final Clock clock;

    public NotificationService(
            NotificationPrefRepository prefs,
            PushTokenRepository pushTokens,
            NotificationLogRepository logs,
            ExpoPushClient pushClient,
            QuietHoursPolicy quietHours,
            Clock clock
    ) {
        this.prefs = prefs;
        this.pushTokens = pushTokens;
        this.logs = logs;
        this.pushClient = pushClient;
        this.quietHours = quietHours;
        this.clock = clock;
    }

    @Transactional
    public NotificationPref getOrCreatePref(User user) {
        return prefs.findById(user.getId()).orElseGet(() -> prefs.save(new NotificationPref(user)));
    }

    /** Cron-style nudge with day-keyed dedup. Idempotent for repeat cron firings. */
    @Transactional
    public void sendCron(User user, NotificationKind kind, String dateKey, String title, String body) {
        NotificationPref pref = getOrCreatePref(user);
        if (!isCronEnabled(pref, kind)) {
            return;
        }
        if (isInQuietHours(user, pref)) {
            return;
        }
        if (logs.existsByUserAndKindAndKey(user, kind, dateKey)) {
            return;
        }
        dispatch(user, kind, dateKey, title, body);
    }

    /** Event-driven nudge with per-(user,kind) debounce window. */
    @Transactional
    public void sendEvent(User user, NotificationKind kind, String key, String title, String body, Duration debounce) {
        NotificationPref pref = getOrCreatePref(user);
        if (!pref.isEventHooksEnabled()) {
            return;
        }
        if (isInQuietHours(user, pref)) {
            return;
        }
        Optional<NotificationLog> latest = logs.findLatest(user, kind);
        if (latest.isPresent()) {
            Instant lastSentAt = latest.get().getSentAt();
            if (lastSentAt.isAfter(clock.instant().minus(debounce))) {
                return;
            }
        }
        dispatch(user, kind, key, title, body);
    }

    private void dispatch(User user, NotificationKind kind, String key, String title, String body) {
        List<PushToken> tokens = pushTokens.findByUser(user);
        if (tokens.isEmpty()) {
            return;
        }
        pushClient.send(tokens.stream().map(PushToken::getToken).toList(), title, body);
        logs.save(new NotificationLog(user, kind, key));
    }

    private boolean isCronEnabled(NotificationPref pref, NotificationKind kind) {
        return switch (kind) {
            case GOAL_NUDGE -> pref.isGoalNudgeEnabled();
            case REFLECTION_NUDGE -> pref.isReflectionNudgeEnabled();
            case FRIEND_GOAL, FRIEND_REFLECTION -> pref.isEventHooksEnabled();
        };
    }

    private boolean isInQuietHours(User user, NotificationPref pref) {
        ZoneId tz = ZoneId.of(user.getTimezone() == null ? "Asia/Seoul" : user.getTimezone());
        LocalTime localNow = LocalTime.from(clock.instant().atZone(tz));
        return quietHours.isQuiet(localNow, pref.getQuietStartHour(), pref.getQuietEndHour());
    }
}
