package com.yeosal.api.survival;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeosal.api.realtime.RealtimePublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains matured rows from {@code pending_realtime_broadcasts} and emits the
 * broad fan-out to {@code /topic/rooms/{roomId}/survival} (Story 1.2 AC7,
 * Architecture §4.14).
 *
 * <p>Tick cadence is {@code @Scheduled(fixedDelay)} keyed on
 * {@code yeosal.realtime.broadcast-dispatcher-delay-ms} (default 60_000) —
 * {@code fixedDelay} prevents pile-up on slow ticks.
 *
 * <p>{@code markEmitted} runs only on broker success — a broker hiccup
 * leaves the row eligible for the next tick. JSON deserialize failures
 * (defensive — payload shapes are controlled in-house) are logged loud and
 * left for ops triage; the row stays unmarked.
 */
@Component
public class PendingRealtimeBroadcastDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(PendingRealtimeBroadcastDispatcher.class);
    static final int BATCH_SIZE = 500;

    private final PendingRealtimeBroadcastRepository pendingBroadcasts;
    private final RealtimePublisher publisher;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PendingRealtimeBroadcastDispatcher(
            PendingRealtimeBroadcastRepository pendingBroadcasts,
            RealtimePublisher publisher,
            Clock clock,
            ObjectMapper objectMapper) {
        this.pendingBroadcasts = pendingBroadcasts;
        this.publisher = publisher;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${yeosal.realtime.broadcast-dispatcher-delay-ms:60000}")
    @Transactional
    public void drain() {
        Instant now = clock.instant();
        List<PendingRealtimeBroadcast> due =
                pendingBroadcasts.findDueForEmission(now, PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) {
            return;
        }
        log.info("[realtime-dispatcher] draining {} pending rows", due.size());

        int emitted = 0;
        int failedBroker = 0;
        int failedDeserialize = 0;

        for (PendingRealtimeBroadcast row : due) {
            SurvivalStateChangePayload payload;
            try {
                payload = objectMapper.treeToValue(
                        row.getPayload(), SurvivalStateChangePayload.class);
            } catch (JsonProcessingException ex) {
                log.error("[realtime-dispatcher] payload deserialize failed rowId={}: {}",
                        row.getId(), ex.toString());
                failedDeserialize += 1;
                continue;
            }
            boolean ok = publisher.publishSurvivalStateBroadcast(payload.roomId(), payload);
            if (ok) {
                pendingBroadcasts.markEmitted(row.getId(), clock.instant());
                emitted += 1;
            } else {
                failedBroker += 1;
            }
        }

        log.info("[realtime-dispatcher] tick emitted={} failedBroker={} failedDeserialize={}",
                emitted, failedBroker, failedDeserialize);
    }
}
