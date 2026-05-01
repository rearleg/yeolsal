package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private static final int PAGE_SIZE = 500;

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
        forEachUser(user -> notifications.sendCron(
                user, NotificationKind.GOAL_NUDGE, dateKey,
                "오늘의 목표를 정해보세요", "오늘 하루의 목표를 적어보세요."));
    }

    /** 21:30 KST every day — reflection nudge. */
    @Scheduled(cron = "0 30 21 * * *", zone = "Asia/Seoul")
    public void runReflectionNudge() {
        String dateKey = today().toString();
        log.info("[notif] reflection-nudge fan-out starting (key={})", dateKey);
        forEachUser(user -> notifications.sendCron(
                user, NotificationKind.REFLECTION_NUDGE, dateKey,
                "오늘 회고를 남겨주세요", "오늘 하루를 짧게 돌아봐요."));
    }

    /**
     * Page through every user in deterministic id order and apply {@code action}.
     * Replaces an in-memory {@code findAll()} fan-out so the scheduler stays
     * bounded as the user count grows. A failure on one user is logged and
     * the loop continues — one bad row does not skip everyone after it.
     */
    private void forEachUser(Consumer<User> action) {
        int pageNumber = 0;
        Page<User> page;
        do {
            page = users.findAll(PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id")));
            for (User user : page.getContent()) {
                try {
                    action.accept(user);
                } catch (Exception ex) {
                    log.warn("[notif] failed to process user id={}: {}", user.getId(), ex.getMessage());
                }
            }
            pageNumber += 1;
        } while (page.hasNext());
    }

    private LocalDate today() {
        return clock.instant().atZone(KST).toLocalDate();
    }
}
