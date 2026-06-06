package com.yeosal.api.kakaoshare;

import com.yeosal.api.room.Room;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Thin proxy boundary so the {@code @Async} dispatch in
 * {@link PreviewCardCacheService} actually crosses a Spring AOP proxy. A
 * direct self-invocation on the cache service would bypass the proxy and
 * silently run synchronously — see story trap #3.
 */
@Component
public class PreviewCardBackgroundRenderer {

    private final PreviewCardCacheService cacheService;

    public PreviewCardBackgroundRenderer(PreviewCardCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Async(PreviewCardRenderExecutorConfig.EXECUTOR_BEAN_NAME)
    public void render(Room room) {
        cacheService.backgroundRenderUnchecked(room);
    }
}
