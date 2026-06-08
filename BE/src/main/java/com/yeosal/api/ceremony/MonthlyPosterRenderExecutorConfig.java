package com.yeosal.api.ceremony;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Story 7.2 — dedicated executor for the monthly Final-3 poster batch.
 * Sized for the NFR-9.1.4 budget: 5,000 rooms × ~500ms per render must
 * complete inside 10 minutes. Eight worker threads keep the batch under
 * ~5.5 minutes at scale while staying memory-cheap (Batik PNGTranscoder
 * ≈ 5MB heap per concurrent transcode = ~40MB peak).
 *
 * <p>Pool is intentionally distinct from {@code previewCardRenderExecutor}
 * — sharing capacity would let a once-per-month burst starve the
 * always-on preview-card pipeline (and vice versa). Same
 * {@link ThreadPoolTaskExecutor} shape as the preview-card config so
 * Spring's lifecycle manages startup/shutdown without manual hooks.
 */
@Configuration
public class MonthlyPosterRenderExecutorConfig {

    /** Name referenced from {@link FinalThreeJob}'s
     *  {@code @Qualifier(...)} constructor parameter. */
    public static final String EXECUTOR_BEAN_NAME = "monthlyPosterRenderExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public TaskExecutor monthlyPosterRenderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("monthly-poster-");
        executor.initialize();
        return executor;
    }
}
