package com.yeosal.api.survival;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.realtime.RealtimePublisher;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT listener for {@link SurvivalStateTransitionEvent} (Story 1.2
 * AC5/AC7). Fires after the evaluator's per-room transaction commits, so a
 * rolled-back transition never lights up the realtime fan-out.
 *
 * <p>Two-channel privacy:
 * <ul>
 *   <li><strong>Non-RED (ACTIVE→YELLOW)</strong> — single
 *       {@code publishSurvivalStateChange(..., privateOnly=false)} call
 *       emits to the affected user's private queue AND the room topic
 *       immediately (YELLOW is not privacy-gated).</li>
 *   <li><strong>RED</strong> — two private emits (affected user + room
 *       owner), then a {@code pending_realtime_broadcasts} row queued with
 *       {@code scheduled_at = broad_visibility_at} so the broad fan-out
 *       fires only after the 24-hour cooldown
 *       (via {@code PendingRealtimeBroadcastDispatcher}).</li>
 * </ul>
 *
 * <p>The listener is wrapped in {@code @Transactional(REQUIRES_NEW)} so the
 * pending-row write has a fresh transaction — Spring's AFTER_COMMIT phase
 * leaves no outer transaction context.
 */
@Component
public class SurvivalStateRealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(SurvivalStateRealtimeListener.class);
    private static final String EVENT_KIND = "SURVIVAL_STATE_CHANGE";

    private final RealtimePublisher publisher;
    private final PendingRealtimeBroadcastRepository pendingBroadcasts;
    private final ObjectMapper objectMapper;

    public SurvivalStateRealtimeListener(
            RealtimePublisher publisher,
            PendingRealtimeBroadcastRepository pendingBroadcasts,
            ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.pendingBroadcasts = pendingBroadcasts;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(SurvivalStateTransitionEvent event) {
        // RED transitions share the same `occurredAt` snapshot for
        // `eliminatedAt` (AC9 single-Instant invariant). Non-RED transitions
        // carry null for both `eliminatedAt` and `broadVisibilityAt`.
        Instant eliminatedAt = event.toStatus() == SurvivalStatus.RED
                ? event.occurredAt()
                : null;
        SurvivalStateChangePayload payload = new SurvivalStateChangePayload(
                event.roomId(),
                event.userId(),
                event.fromStatus(),
                event.toStatus(),
                event.occurredAt(),
                eliminatedAt,
                event.broadVisibilityAt());

        if (event.toStatus() == SurvivalStatus.RED) {
            // Immediate private to the eliminated user.
            publisher.publishSurvivalStateChange(
                    event.roomId(), event.userId(), payload, true);

            // Immediate private to the room owner (AC7).
            Long ownerUserId = event.ownerUserId();
            if (ownerUserId != null && ownerUserId != event.userId()) {
                publisher.publishSurvivalStateChange(
                        event.roomId(), ownerUserId, payload, true);
            }

            // Delayed broad fan-out — landed in pending_realtime_broadcasts.
            ObjectNode node = (ObjectNode) objectMapper.valueToTree(payload);
            node.put("eventKind", EVENT_KIND);
            pendingBroadcasts.save(new PendingRealtimeBroadcast(
                    event.broadVisibilityAt(), node));

            log.info("[survival-realtime] RED roomId={} userId={} broadVisibilityAt={}",
                    event.roomId(), event.userId(), event.broadVisibilityAt());
            return;
        }

        // Non-RED (currently ACTIVE → YELLOW): single emit covers both channels.
        publisher.publishSurvivalStateChange(
                event.roomId(), event.userId(), payload, false);

        log.info("[survival-realtime] {} → {} roomId={} userId={}",
                event.fromStatus(), event.toStatus(),
                event.roomId(), event.userId());
    }
}
