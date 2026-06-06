package com.yeosal.api.kakaoshare;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Kakao-facing endpoint that returns a 302 redirect to the cached PNG
 * for a room's invite preview card. The URL is shareable and the PNG carries
 * non-PII content (room name + member count + rule summary), so the chain is
 * deliberately public via {@code SecurityConfig} (Story 6.1 AC2).
 *
 * <p>Returns 503 with {@code Retry-After: 5} when the cache row is missing
 * and another instance owns the render lock. KakaoTalk's fetcher will retry,
 * and the first render typically completes well inside that window.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class PreviewCardController {

    private final PreviewCardCacheService cacheService;

    public PreviewCardController(PreviewCardCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/{id}/invites/preview-card")
    public ResponseEntity<Void> servePreviewCard(@PathVariable long id) {
        return cacheService.resolve(id)
                .map(url -> ResponseEntity
                        .status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                        .<Void>build())
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, "5")
                        .<Void>build());
    }
}
