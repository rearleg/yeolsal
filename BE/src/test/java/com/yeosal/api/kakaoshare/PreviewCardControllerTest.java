package com.yeosal.api.kakaoshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.ServiceUnavailableException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Story 6.1 AC2 — direct unit coverage of the controller's response shape so
 * the 302 / 503 dispatch is validated even when the WebMvc slice cannot run
 * (Docker-less dev host). Full HTTP-layer assertions live in
 * {@code PreviewCardEndToEndIT} (opt-in).
 */
@ExtendWith(MockitoExtension.class)
class PreviewCardControllerTest {

    private static final long ROOM_ID = 42L;

    @Mock private PreviewCardCacheService cacheService;

    private PreviewCardController controller;

    @BeforeEach
    void setUp() {
        controller = new PreviewCardController(cacheService);
    }

    @Test
    @DisplayName("cache hit → 302 with Location and a public 1h Cache-Control")
    void cacheHit_returns302WithLocationAndCacheControl() {
        when(cacheService.resolve(ROOM_ID))
                .thenReturn(Optional.of("https://example.com/preview/42.png"));

        ResponseEntity<Void> response = controller.servePreviewCard(ROOM_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("https://example.com/preview/42.png");
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("max-age=3600, public");
    }

    @Test
    @DisplayName("room not found (empty optional) → 503 + Retry-After: 5")
    void roomNotFound_returns503WithRetryAfter() {
        when(cacheService.resolve(ROOM_ID)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.servePreviewCard(ROOM_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    @DisplayName("ServiceUnavailableException from cacheService surfaces to the global handler (not caught here)")
    void cacheServiceThrowing503Propagates() {
        when(cacheService.resolve(ROOM_ID))
                .thenThrow(new ServiceUnavailableException("render in flight"));

        // Controller does NOT catch — ApiExceptionHandler maps to 503.
        assertThatThrownBy(() -> controller.servePreviewCard(ROOM_ID))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("render in flight");
    }
}
