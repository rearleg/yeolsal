package com.yeosal.api.analytics;

import com.posthog.java.PostHog;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Story 8.5 AC6 — PostHog-backed {@link AnalyticsService}.
 *
 * <p>Constructor-injects the {@link PostHog} client bean (built by
 * {@link AnalyticsConfig#postHogClient}). Every method swallows
 * {@link RuntimeException} so a PostHog transient failure (network
 * blip, queue full) never propagates to a controller / listener
 * caller — analytics is best-effort by design.
 *
 * <p>Story 8.5 ships ZERO emission sites; the wrapper is the foundation
 * downstream stories or the backfill PR will consume.
 */
public class PostHogAnalyticsService implements AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(PostHogAnalyticsService.class);

    private final PostHog client;

    public PostHogAnalyticsService(PostHog client) {
        this.client = client;
    }

    @Override
    public void capture(String distinctId, String eventName, Map<String, Object> properties) {
        try {
            client.capture(distinctId, eventName, properties);
        } catch (RuntimeException ex) {
            log.warn("[analytics] capture failed event={} distinctId={}: {}",
                    eventName, distinctId, ex.toString());
        }
    }

    @Override
    public void identify(String distinctId, Map<String, Object> properties) {
        try {
            client.identify(distinctId, properties);
        } catch (RuntimeException ex) {
            log.warn("[analytics] identify failed distinctId={}: {}",
                    distinctId, ex.toString());
        }
    }
}
