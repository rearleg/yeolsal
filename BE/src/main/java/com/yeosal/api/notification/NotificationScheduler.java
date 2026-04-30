package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires standard notifications on a per-user fan-out at the configured cron
 * times. Quiet hours, prefs, and dedup are enforced inside
 * {@link NotificationService#sendCron}.
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final NotificationService notifications;
    private final UserRepository users;
    private final Clock clock;

    public NotificationScheduler(NotificationService notifications, UserRepository users, Clock clock) {
        this.notifications = notifications;
        this.users = users;
        this.clock = clock;
    }

    /** 08:00 KST every day — goal nudge. */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void runGoalNudge() {
        String dateKey = today().toString();
        log.info("[notif] goal-nudge fan-out starting (key={})", dateKey);
        for (User user : users.findAll()) {
            notifications.sendCron(user, NotificationKind.GOAL_NUDGE, dateKey,
                    "오늘의 목표를 정해보세요", "오늘 하루의 목표를 적어보세요.");
        }
    }

    /** 21:30 KST every day — reflection nudge. */
    @Scheduled(cron = "0 30 21 * * *", zone = "Asia/Seoul")
    public void runReflectionNudge() {
        String dateKey = today().toString();
        log.info("[notif] reflection-nudge fan-out starting (key={})", dateKey);
        for (User user : users.findAll()) {
            notifications.sendCron(user, NotificationKind.REFLECTION_NUDGE, dateKey,
                    "오늘 회고를 남겨주세요", "오늘 하루를 짧게 돌아봐요.");
        }
    }

    private LocalDate today() {
        return clock.instant().atZone(KST).toLocalDate();
    }
}
