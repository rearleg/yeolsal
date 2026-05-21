package com.yeosal.api.revival;

import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationService;
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
 * Story 3.2 AC2 — AFTER_COMMIT listener for {@link FriendGiftSentEvent}.
 * Sends the receiver donor-confirmation push (FR-8.3.5 — donor-name
 * visible to receiver only).
 *
 * <p>Mirrors the {@code KudosRealtimeListener} shape exactly:
 * {@link Propagation#REQUIRES_NEW} so the listener has its own
 * transaction (Spring's AFTER_COMMIT phase leaves no outer transaction
 * context) and individual try/catch around the push call so a transient
 * provider failure cannot poison the listener's transaction or block
 * future events.
 *
 * <p>The receiver donor-confirmation uses PUSH (not WS) because the
 * receiver may not be in-app when the gift lands — Maya-persona
 * scenario: receiver is offline, donor sees them eliminated, gifts, the
 * receiver opens the app hours later via the push notification.
 */
@Component
public class FriendGiftRealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(FriendGiftRealtimeListener.class);
    private static final String PUSH_TITLE_SUFFIX = "가 너의 회생권을 선물했어";
    private static final String PUSH_BODY = "방으로 돌아와도 좋아요";

    private final NotificationService notificationService;
    private final UserRepository users;

    public FriendGiftRealtimeListener(
            NotificationService notificationService,
            UserRepository users) {
        this.notificationService = notificationService;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSent(FriendGiftSentEvent event) {
        Optional<User> giver = users.findById(event.giverUserId());
        Optional<User> receiver = users.findById(event.receiverUserId());
        if (giver.isEmpty() || receiver.isEmpty()) {
            log.warn(
                    "[friend-gift-realtime] missing user giverPresent={} receiverPresent={} roomId={} revivalEventId={}",
                    giver.isPresent(), receiver.isPresent(),
                    event.roomId(), event.revivalEventId());
            return;
        }

        String title = giver.get().getNickname() + PUSH_TITLE_SUFFIX;
        // Per AC2.4 — receiver-side dedup keyed on the just-inserted
        // revival event id. A single revival can only be friend-gifted by
        // one donor at a time (partial-unique-index defence), so this key
        // is naturally unique.
        String key = "revival:" + event.revivalEventId();

        try {
            notificationService.sendEvent(
                    receiver.get(),
                    NotificationKind.FRIEND_GIFT_RECEIVED,
                    key,
                    title,
                    PUSH_BODY,
                    Duration.ZERO);
        } catch (RuntimeException ex) {
            log.warn(
                    "[friend-gift-realtime] sendEvent failed receiverUserId={} revivalEventId={}: {}",
                    event.receiverUserId(), event.revivalEventId(), ex.toString());
        }

        if (log.isInfoEnabled()) {
            log.info(
                    "[friend-gift-realtime] roomId={} giverUserId={} receiverUserId={} revivalEventId={}",
                    event.roomId(), event.giverUserId(), event.receiverUserId(),
                    event.revivalEventId());
        }
    }
}
