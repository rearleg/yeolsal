package com.yeosal.api.analytics;

import java.util.Map;

/**
 * Story 8.5 AC6 — fallback {@link AnalyticsService} used in dev / OSS
 * forks / test profile / any environment where
 * {@code yeosal.analytics.enabled} is not {@code true}.
 *
 * <p>All methods silently no-op. Construction is zero-arg so it can be
 * wired into a {@code @SpringBootTest} without dragging the PostHog
 * client constructor (Trap #14: missing env var must not crash boot).
 */
public class NoOpAnalyticsService implements AnalyticsService {

    @Override
    public void capture(String distinctId, String eventName, Map<String, Object> properties) {
        // no-op
    }

    @Override
    public void identify(String distinctId, Map<String, Object> properties) {
        // no-op
    }
}
