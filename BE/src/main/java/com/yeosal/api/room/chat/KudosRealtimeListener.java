package com.yeosal.api.room.chat;

import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Story 3.5 — AFTER_COMMIT listener for {@link KudosSentEvent}.
 *
 * <p>Fires after the {@link KudosService#sendKudos} transaction commits,
 * so a rolled-back kudos write NEVER lights up the realtime fan-out or
 * push notification. Mirrors the {@code SurvivalStateRealtimeListener}
 * pattern: {@link Propagation#REQUIRES_NEW} so the listener has its own
 * transaction (Spring's AFTER_COMMIT phase leaves no outer transaction
 * context) and individual try/catch wrappers so a broker or push hiccup
 * cannot poison the chained delivery.
 *
 * <p>The push title/body are locked Korean strings approved by the
 * brand-voice review (FR-8.3.4 invitation tone, FR-8.8.2 AVOID-lexicon
 * exclusion).
 */
@Component
public class KudosRealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(KudosRealtimeListener.class);
    private static final String PUSH_TITLE = "응원이 도착했어요 🌿";
    /** Suffix appended to the sender nickname for the push body. Matches
     *  the chat-row body produced by {@code KudosService.renderBody}. */
    private static final String BODY_SUFFIX = "이 응원을 보냈어요";

    private final RealtimePublisher publisher;
    private final NotificationService notificationService;
    private final UserRepository users;

    public KudosRealtimeListener(
            RealtimePublisher publisher,
            NotificationService notificationService,
            UserRepository users) {
        this.publisher = publisher;
        this.notificationService = notificationService;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSent(KudosSentEvent event) {
        // Load sender + target inside the REQUIRES_NEW transaction so the
        // nickname read is safely fetched (open-in-view: false project
        // rule). Failure to load either is logged and the listener returns
        // — the chat row already committed, so the receiver still sees the
        // kudos on next room open.
        Optional<User> sender = users.findById(event.senderUserId());
        Optional<User> target = users.findById(event.targetUserId());
        if (sender.isEmpty() || target.isEmpty()) {
            log.warn(
                    "[kudos-realtime] missing user senderPresent={} targetPresent={} roomId={}",
                    sender.isPresent(), target.isPresent(), event.roomId());
            return;
        }
        String senderNickname = sender.get().getNickname();
        String pushBody = senderNickname + BODY_SUFFIX;

        // Realtime emit — warn-and-continue on broker failure. The
        // RealtimePublisher.sendTopic helper already swallows broker
        // exceptions, but the extra try/catch defends against a future
        // refactor that swaps the helper.
        try {
            publisher.publishKudos(
                    event.roomId(),
                    new KudosSentPayload(
                            event.senderUserId(),
                            event.targetUserId(),
                            event.messagePreview(),
                            event.occurredAt()));
        } catch (RuntimeException ex) {
            log.warn("[kudos-realtime] publishKudos failed roomId={}: {}",
                    event.roomId(), ex.toString());
        }

        // Push notification — Duration.ZERO debounce because the V12
        // partial unique index is the dedupe authority (1/day/(sender,
        // target)). NotificationService.sendEvent runs in its own
        // REQUIRES_NEW so it cannot roll back this listener's tx.
        try {
            notificationService.sendEvent(
                    target.get(),
                    NotificationKind.KUDOS_RECEIVED,
                    Long.toString(event.occurredAt().toEpochMilli()),
                    PUSH_TITLE,
                    pushBody,
                    Duration.ZERO);
        } catch (RuntimeException ex) {
            log.warn("[kudos-realtime] sendEvent failed targetUserId={}: {}",
                    event.targetUserId(), ex.toString());
        }

        if (log.isInfoEnabled()) {
            log.info("[kudos-realtime] roomId={} senderUserId={} targetUserId={}",
                    event.roomId(), event.senderUserId(), event.targetUserId());
        }
    }
}
