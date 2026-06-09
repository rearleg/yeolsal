package com.yeosal.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Story 8.5 BE-4 (AC6 + AC15 row 6). Verifies the {@code
 * yeosal.analytics.enabled} flag drives bean selection via Spring Boot
 * {@code ApplicationContextRunner} (the lighter cousin of
 * {@code @SpringBootTest} — no servlet, no Flyway, no Testcontainers,
 * just the bean graph). Mirrors the pattern used by Spring Boot's own
 * conditional-on-property docs.
 */
class AnalyticsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(AnalyticsConfig.class);

    @Test
    @DisplayName("yeosal.analytics.enabled=true → PostHogAnalyticsService bean")
    void enabledTrueWiresPostHogService() {
        contextRunner
                .withPropertyValues(
                        "yeosal.analytics.enabled=true",
                        "yeosal.analytics.host=https://analytics.example.com",
                        "yeosal.analytics.project-api-key=phc_example")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AnalyticsService.class);
                    assertThat(ctx.getBean(AnalyticsService.class))
                            .isInstanceOf(PostHogAnalyticsService.class);
                });
    }

    @Test
    @DisplayName("yeosal.analytics.enabled=false → NoOpAnalyticsService bean")
    void enabledFalseWiresNoOpService() {
        contextRunner
                .withPropertyValues("yeosal.analytics.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AnalyticsService.class);
                    assertThat(ctx.getBean(AnalyticsService.class))
                            .isInstanceOf(NoOpAnalyticsService.class);
                });
    }

    @Test
    @DisplayName("missing yeosal.analytics.enabled (Trap #14) → NoOpAnalyticsService bean")
    void missingFlagWiresNoOpService() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AnalyticsService.class);
            assertThat(ctx.getBean(AnalyticsService.class))
                    .isInstanceOf(NoOpAnalyticsService.class);
        });
    }
}
