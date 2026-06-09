package com.yeosal.api.analytics;

import java.util.Map;

/**
 * Story 8.5 AC6 — BE seam for PostHog server-side capture.
 *
 * <p>Two implementations:
 *
 * <ul>
 *   <li>{@code PostHogAnalyticsService} — production path, wraps the
 *       {@code com.posthog.java:posthog} client.</li>
 *   <li>{@code NoOpAnalyticsService} — dev / OSS forks / test profile.
 *       All methods are silent no-ops.</li>
 * </ul>
 *
 * <p>Selection is controlled by {@link AnalyticsConfig} via
 * {@code @ConditionalOnProperty("yeosal.analytics.enabled")}. Missing
 * property means missing env var → no-op bean wired (fail-closed). Per
 * Story 8.5 Trap #14, {@code matchIfMissing=false} is critical: a
 * missing flag must route to {@link NoOpAnalyticsService} so callers
 * never get a {@code null} bean.
 *
 * <p>Story 8.5 ships ZERO emission sites in this story (Trap #1 +
 * decision doc §6 OOS #2). The service interface + bean wiring + tests
 * are the foundation; downstream backfill stories consume.
 */
public interface AnalyticsService {

    /**
     * Capture an event for the given distinct user.
     *
     * @param distinctId stable per-user identifier (matches the FE
     *                   PostHog distinct_id; typically the BE primary
     *                   key as a String).
     * @param eventName  snake-case dotted event name from the locked
     *                   taxonomy in {@code docs/analytics.md}.
     * @param properties event properties; may be empty but never null.
     */
    void capture(String distinctId, String eventName, Map<String, Object> properties);

    /**
     * Identify a user with the given person properties. Mirrors the FE
     * {@code identifyUser} contract — no PII (no email / nickname /
     * phone) per Story 8.5 AC2 Trap #8.
     */
    void identify(String distinctId, Map<String, Object> properties);
}
