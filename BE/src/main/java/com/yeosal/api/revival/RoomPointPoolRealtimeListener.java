package com.yeosal.api.revival;

import com.yeosal.api.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT listener for {@link PointPoolChangeEvent} (Story 3.1 AC2/AC3
 * + AC9). Fires after the revival service's {@code @Transactional} method
 * commits, so a rolled-back revival never lights up the realtime fan-out.
 *
 * <p>The listener wraps in {@code @Transactional(REQUIRES_NEW)} for
 * symmetry with {@link com.yeosal.api.survival.SurvivalStateRealtimeListener}
 * — Spring's AFTER_COMMIT phase leaves no outer transaction context.
 * No DB writes happen on this path (yet); the wrapper exists so future
 * stories (Story 4.1 pool counter cache, Story 7.x ceremony aggregation)
 * can add side-effects without re-wiring the propagation rule.
 */
@Component
public class RoomPointPoolRealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(RoomPointPoolRealtimeListener.class);

    private final RealtimePublisher publisher;

    public RoomPointPoolRealtimeListener(RealtimePublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPointPoolChange(PointPoolChangeEvent event) {
        PointPoolChangePayload payload = new PointPoolChangePayload(
                event.roomId(),
                event.delta(),
                event.newTotal(),
                event.sourceRevivalEventId(),
                event.occurredAt());
        publisher.publishPointPoolChange(event.roomId(), payload);

        if (log.isInfoEnabled()) {
            log.info("[point-pool-realtime] roomId={} delta={} newTotal={} src={}",
                    event.roomId(), event.delta(), event.newTotal(),
                    event.sourceRevivalEventId());
        }
    }
}
