package com.yeosal.api.analytics;

import com.posthog.java.PostHog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Story 8.5 AC6 + AC7 — bean wiring for the analytics module.
 *
 * <p>Two independent beans:
 *
 * <ol>
 *   <li>{@link PostHog} client — built only when {@code
 *       yeosal.analytics.enabled=true}. The {@code matchIfMissing=false}
 *       on every conditional is critical (Trap #14): a missing or
 *       {@code false} flag must keep the client OUT of the context so
 *       BE boot does not require a real PostHog endpoint.</li>
 *   <li>{@link AnalyticsService} — either {@link PostHogAnalyticsService}
 *       (when the client bean exists) or {@link NoOpAnalyticsService}
 *       (otherwise — the {@code @ConditionalOnMissingBean} on the no-op
 *       factory routes correctly without explicit profile gating).</li>
 * </ol>
 *
 * <p>The {@code @ConditionalOnProperty(havingValue="true")} idiom means
 * unit + slice tests that don't set {@code yeosal.analytics.enabled=true}
 * automatically get the no-op service — no per-test PostHog mocking
 * required for non-analytics code paths.
 */
@Configuration
public class AnalyticsConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(
            prefix = "yeosal.analytics",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public PostHog postHogClient(
            @Value("${yeosal.analytics.host:}") String host,
            @Value("${yeosal.analytics.project-api-key:}") String projectApiKey) {
        PostHog.Builder builder = new PostHog.Builder(projectApiKey);
        if (host != null && !host.isBlank()) {
            builder.host(host);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "yeosal.analytics",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false)
    public AnalyticsService postHogAnalyticsService(PostHog client) {
        return new PostHogAnalyticsService(client);
    }

    @Bean
    @ConditionalOnMissingBean(AnalyticsService.class)
    public AnalyticsService noOpAnalyticsService() {
        return new NoOpAnalyticsService();
    }
}
