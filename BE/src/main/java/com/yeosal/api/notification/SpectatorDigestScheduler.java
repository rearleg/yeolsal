package com.yeosal.api.notification;

import com.yeosal.api.survival.SpectatorDigestService;
import com.yeosal.api.survival.SpectatorDigestService.DigestEntry;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Story 2.2 — 09:00 KST cron, one push per spectator per day per room with
 * activity yesterday. Mirrors the {@link NotificationScheduler} paged
 * fan-out pattern (PAGE_SIZE = 500, deterministic id sort, per-user
 * try/catch). Pref / quiet-hours / dedup gates live inside
 * {@link NotificationService#sendCron} — this scheduler only composes the
 * title, body, and dedup key.
 */
@Component
public class SpectatorDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpectatorDigestScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 500;

    private final NotificationService notifications;
    private final SpectatorDigestService digestService;
    private final UserRepository users;
    private final Clock clock;

    public SpectatorDigestScheduler(
            NotificationService notifications,
            SpectatorDigestService digestService,
            UserRepository users,
            Clock clock) {
        this.notifications = notifications;
        this.digestService = digestService;
        this.users = users;
        this.clock = clock;
    }

    /**
     * 09:00 KST every day — spectator daily digest. Summarizes the prior
     * 06:00→06:00 KST window so the data covers "yesterday" in the
     * survival-evaluator day-boundary sense, not the calendar day.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void runDailyDigest() {
        LocalDate priorEntryDate = clock.instant().atZone(KST).toLocalDate().minusDays(1);
        log.info("[notif] spectator-digest fan-out starting (priorDate={})", priorEntryDate);

        int pageNumber = 0;
        Page<User> page;
        do {
            page = users.findAll(PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id")));
            for (User user : page.getContent()) {
                try {
                    dispatchForUser(user, priorEntryDate);
                } catch (Exception ex) {
                    log.warn("[notif] spectator-digest failed user_id={}: {}", user.getId(), ex.getMessage());
                }
            }
            pageNumber += 1;
        } while (page.hasNext());
    }

    private void dispatchForUser(User user, LocalDate priorEntryDate) {
        List<DigestEntry> entries = digestService.evaluateForUser(user.getId(), priorEntryDate);
        for (DigestEntry entry : entries) {
            String dedupKey = priorEntryDate + ":" + user.getId() + ":" + entry.roomId();
            String title = String.format("오늘도 %s 함께 살아남고 있어요", entry.roomName());
            String body = composeBody(entry);
            notifications.sendCron(user, NotificationKind.SPECTATOR_DIGEST, dedupKey, title, body);
        }
    }

    /**
     * Body composition (AC5): four mutually exclusive branches keep the push
     * readable across every activity shape the aggregator can emit. The state-
     * only branch (Story 2.2 review finding #2) surfaces a generic warm-tone
     * line because (a) AC4 keeps state-only rooms in the digest list and
     * (b) AC5 forbids exposing {@code stateChangeCount} to the body — without
     * the dedicated branch the fall-through rendered "메시지 0개 · 새 글 0개"
     * which leaked zero stats to the user.
     */
    private static String composeBody(DigestEntry entry) {
        boolean hasChat = entry.chatMessageCount() > 0;
        boolean hasDaily = entry.dailyEntryCount() > 0;
        if (hasChat && hasDaily) {
            return String.format("어제 메시지 %d개 · 새 글 %d개",
                    entry.chatMessageCount(), entry.dailyEntryCount());
        }
        if (hasChat) {
            return String.format("어제 메시지 %d개", entry.chatMessageCount());
        }
        if (hasDaily) {
            return String.format("어제 새 글 %d개", entry.dailyEntryCount());
        }
        return "어제 방에 작은 변화가 있었어요";
    }
}
