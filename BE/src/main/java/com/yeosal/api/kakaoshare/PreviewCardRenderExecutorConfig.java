package com.yeosal.api.kakaoshare;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated executor for background preview card re-renders. Sizing is
 * intentionally small — preview rebuilds are I/O-bound (Batik + disk write)
 * and concurrent renders for the same room are serialised by the Postgres
 * advisory lock, so two threads are enough to absorb fan-out across rooms
 * without competing with the default Spring task executor for capacity.
 */
@Configuration
public class PreviewCardRenderExecutorConfig {

    /** Name referenced from {@link PreviewCardBackgroundRenderer}'s
     *  {@code @Async("previewCardRenderExecutor")}. */
    public static final String EXECUTOR_BEAN_NAME = "previewCardRenderExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public TaskExecutor previewCardRenderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("preview-card-render-");
        executor.initialize();
        return executor;
    }
}
