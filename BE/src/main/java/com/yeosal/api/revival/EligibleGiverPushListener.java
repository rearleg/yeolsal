package com.yeosal.api.revival;

import com.yeosal.api.analytics.AnalyticsService;
import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateTransitionEvent;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Story 3.2 AC2 — AFTER_COMMIT listener that fans out the eligible-giver
 * push when a friend transitions to RED.
 *
 * <p>Runs in parallel with {@code SurvivalStateRealtimeListener} (Spring
 * fans out one event to all matching listeners). The two listeners are
 * independent:
 *
 * <ul>
 *   <li>{@code SurvivalStateRealtimeListener} — owns the broad
 *       state-change emission (private + delayed-broad WS frames).</li>
 *   <li>This listener — owns the per-giver friend-gift push fan-out.</li>
 * </ul>
 *
 * <p>{@link Propagation#REQUIRES_NEW} for symmetry with the survival/
 * kudos realtime listeners — Spring's AFTER_COMMIT phase leaves no outer
 * transaction context, and {@link NotificationService#sendEvent} writes
 * a {@code notification_log} row that needs its own transaction.
 *
 * <p>SPECTATOR transitions are filtered OUT — the eligible-giver fan-out
 * fires only on RED transitions (FR-8.3.4: "when the post-commit event
 * listener runs" in the context of "transitions to RED"). SPECTATOR
 * transitions happen 24h+ later via the daily evaluator and are not the
 * load-bearing emotional moment.
 *
 * <p>Idempotency is enforced via the {@code notification_log
 * (user_id, kind, key)} unique constraint: the dedup key
 * {@code "{roomId}:{receiverUserId}:{eliminatedAtEpochMillis}"} is the
 * same RED elimination across listener retries, app restarts, and
 * multi-instance Spring deployments. The same RED-transition event MUST
 * NEVER fire two pushes to the same giver.
 */
@Component
public class EligibleGiverPushListener {

    private static final Logger log = LoggerFactory.getLogger(EligibleGiverPushListener.class);
    private static final String PUSH_BODY = "잠깐 모달을 열어볼래?";
    private static final String TITLE_SUFFIX = "가 회생을 기다리고 있어요";

    private final FriendGiftEligibilityQuery eligibilityQuery;
    private final UserRepository users;
    private final SurvivalStateRepository survivalStates;
    private final NotificationService notificationService;
    private final AnalyticsService analyticsService;

    public EligibleGiverPushListener(
            FriendGiftEligibilityQuery eligibilityQuery,
            UserRepository users,
            SurvivalStateRepository survivalStates,
            NotificationService notificationService,
            AnalyticsService analyticsService) {
        this.eligibilityQuery = eligibilityQuery;
        this.users = users;
        this.survivalStates = survivalStates;
        this.notificationService = notificationService;
        this.analyticsService = analyticsService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(SurvivalStateTransitionEvent event) {
        // (1) Filter — RED transitions only.
        if (event.toStatus() != SurvivalStatus.RED) {
            return;
        }

        long roomId = event.roomId();
        long receiverUserId = event.userId();
        long eliminatedAtMillis = event.occurredAt().toEpochMilli();
        String key = roomId + ":" + receiverUserId + ":" + eliminatedAtMillis;

        // (2) Receiver nickname — for the push title. Failing to load the
        //     receiver should NOT kill the fan-out; we log and continue
        //     with a generic title fallback ("방원" — same fallback the FE
        //     uses in AC8 when the push payload is missing the nickname).
        String receiverNickname = users.findById(receiverUserId)
                .map(User::getNickname)
                .orElse("방원");

        // (3) Eligible-giver lookup — batched single SQL.
        List<Long> giverIds = eligibilityQuery
                .findEligibleGiverUserIds(roomId, receiverUserId);
        if (giverIds.isEmpty()) {
            if (log.isInfoEnabled()) {
                log.info("[friend-gift-push] zero eligible givers roomId={} receiverUserId={}",
                        roomId, receiverUserId);
            }
            return;
        }

        // (4) Batched user-load to avoid N+1 in the per-giver loop.
        List<User> givers = users.findAllById(giverIds);
        if (givers.isEmpty()) {
            return;
        }

        String title = receiverNickname + TITLE_SUFFIX;

        // (5) Per-giver push. Each call is its own REQUIRES_NEW tx inside
        //     NotificationService.sendEvent, so one failure (push provider
        //     transient error, etc.) does NOT poison the fan-out — the
        //     other givers still receive their invitation.
        for (User giver : givers) {
            try {
                notificationService.sendEvent(
                        giver,
                        NotificationKind.FRIEND_GIFT_PROMPT,
                        key,
                        title,
                        PUSH_BODY,
                        Duration.ZERO);
                // Analytics — friend-gift conversion funnel, BE-emitted leg
                // (docs/analytics.md: friend_gift.push_sent is a server-side
                // event the FE cannot see). distinctId is the giver who
                // receives the prompt. capture() is swallow-safe by contract.
                analyticsService.capture(
                        String.valueOf(giver.getId()),
                        "friend_gift.push_sent",
                        Map.of("roomId", roomId, "receiverUserId", receiverUserId));
            } catch (RuntimeException ex) {
                log.warn(
                        "[friend-gift-push] sendEvent failed giverUserId={} receiverUserId={} roomId={}: {}",
                        giver.getId(), receiverUserId, roomId, ex.toString());
            }
        }

        // Touch the survival_state repository so the @Transactional
        // boundary has a participant (some Spring versions short-circuit
        // empty @TransactionalEventListener methods otherwise) — this
        // keeps the @Transactional wrapper a documentary statement of
        // intent rather than a no-op.
        survivalStates.findByRoomIdAndUserId(roomId, receiverUserId);

        if (log.isInfoEnabled()) {
            log.info(
                    "[friend-gift-push] roomId={} receiverUserId={} eligibleGiverCount={}",
                    roomId, receiverUserId, givers.size());
        }
    }
}
