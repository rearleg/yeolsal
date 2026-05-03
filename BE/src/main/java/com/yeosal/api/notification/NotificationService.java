package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates push delivery for cron nudges and event hooks. Three gates are
 * applied before any send: per-user pref toggles, quiet hours (in the user's
 * timezone), and idempotency / debounce against the {@code notification_log}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

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

    /**
     * Returns the user's pref row, lazily creating defaults when absent.
     * Race resolution lives in Postgres ({@code ON CONFLICT DO NOTHING})
     * rather than the Hibernate session, so a concurrent peer insert no
     * longer poisons the calling transaction.
     */
    @Transactional
    public NotificationPref getOrCreatePref(User user) {
        Long userId = user.getId();
        return prefs.findById(userId).orElseGet(() -> {
            prefs.insertDefaultIfAbsent(userId);
            return prefs.findById(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "notification_prefs row missing after upsert for user_id=" + userId));
        });
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

    /**
     * Event-driven nudge with per-(user,kind) debounce window.
     * <p>
     * Runs in {@link Propagation#REQUIRES_NEW} so a single fan-out recipient's
     * failure (bad timezone, transient push provider error, etc.) only rolls
     * back this nested transaction. Without this, a {@code RuntimeException}
     * here would mark the outer caller's transaction (e.g. the actor's
     * reflection/goal write) rollback-only, and the actor's request would die
     * at commit time with {@code UnexpectedRollbackException} → 500.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        // `data` is forwarded verbatim by Expo to the device. The FE listener
        // (useNotificationInvalidation) reads `data.kind` to invalidate just
        // the right query caches instead of broad-invalidating every cache
        // on every push.
        Map<String, Object> data = Map.of(
                "kind", kind.name(),
                "key", key
        );
        boolean sent = pushClient.send(
                tokens.stream().map(PushToken::getToken).toList(), title, body, data);
        if (sent) {
            // Only record the dedup row when delivery actually went through;
            // otherwise the next cron / event cycle should be allowed to retry.
            logs.save(new NotificationLog(user, kind, key));
        }
    }

    private boolean isCronEnabled(NotificationPref pref, NotificationKind kind) {
        return switch (kind) {
            case GOAL_NUDGE -> pref.isGoalNudgeEnabled();
            case REFLECTION_NUDGE -> pref.isReflectionNudgeEnabled();
            case FRIEND_GOAL, FRIEND_REFLECTION,
                 FRIEND_REQUEST_RECEIVED, FRIEND_REQUEST_ACCEPTED -> pref.isEventHooksEnabled();
        };
    }

    private boolean isInQuietHours(User user, NotificationPref pref) {
        ZoneId tz = parseZoneOrDefault(user.getTimezone());
        LocalTime localNow = LocalTime.from(clock.instant().atZone(tz));
        return quietHours.isQuiet(localNow, pref.getQuietStartHour(), pref.getQuietEndHour());
    }

    /**
     * Falls back to {@link #DEFAULT_ZONE} when the user's stored timezone is
     * blank or no longer a valid IANA id. Throwing {@link DateTimeException}
     * here would surface a 500 to a caller who only wanted to know whether to
     * send a push, so silent fallback is preferred.
     */
    private static ZoneId parseZoneOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            log.warn("[notif] invalid timezone '{}' — falling back to {}", raw, DEFAULT_ZONE);
            return DEFAULT_ZONE;
        }
    }
}
