package com.yeosal.api.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.posthog.java.PostHog;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 8.5 BE-3 (AC6 + AC15 row 5). Verifies the two
 * {@link AnalyticsService} implementations delegate / no-op correctly
 * and swallow client-side {@link RuntimeException} without throwing.
 */
class AnalyticsServiceTest {

    @Test
    @DisplayName("PostHogAnalyticsService.capture delegates to client")
    void postHogCaptureDelegates() {
        PostHog client = mock(PostHog.class);
        PostHogAnalyticsService svc = new PostHogAnalyticsService(client);

        Map<String, Object> props = Map.of("authMethod", "EMAIL");
        svc.capture("42", "signup.completed", props);

        verify(client).capture(eq("42"), eq("signup.completed"), eq(props));
    }

    @Test
    @DisplayName("PostHogAnalyticsService.identify delegates to client")
    void postHogIdentifyDelegates() {
        PostHog client = mock(PostHog.class);
        PostHogAnalyticsService svc = new PostHogAnalyticsService(client);

        Map<String, Object> props = Map.of("account_age_days", 7);
        svc.identify("42", props);

        verify(client).identify(eq("42"), eq(props));
    }

    @Test
    @DisplayName("PostHogAnalyticsService.capture swallows RuntimeException")
    void postHogCaptureSwallowsRuntimeException() {
        PostHog client = mock(PostHog.class);
        doThrow(new RuntimeException("network blip"))
                .when(client)
                .capture(anyString(), anyString(), any());
        PostHogAnalyticsService svc = new PostHogAnalyticsService(client);

        assertThatCode(() -> svc.capture("42", "signup.completed", Map.of()))
                .doesNotThrowAnyException();
        verify(client).capture(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PostHogAnalyticsService.identify swallows RuntimeException")
    void postHogIdentifySwallowsRuntimeException() {
        PostHog client = mock(PostHog.class);
        doThrow(new RuntimeException("queue full"))
                .when(client)
                .identify(anyString(), any());
        PostHogAnalyticsService svc = new PostHogAnalyticsService(client);

        assertThatCode(() -> svc.identify("42", Map.of()))
                .doesNotThrowAnyException();
        verify(client).identify(anyString(), any());
    }

    @Test
    @DisplayName("NoOpAnalyticsService.capture is silent (no interactions)")
    void noOpCaptureIsSilent() {
        PostHog client = mock(PostHog.class);
        NoOpAnalyticsService svc = new NoOpAnalyticsService();

        svc.capture("42", "signup.completed", Map.of("authMethod", "EMAIL"));

        verifyNoInteractions(client);
        verify(client, never()).capture(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("NoOpAnalyticsService.identify is silent")
    void noOpIdentifyIsSilent() {
        PostHog client = mock(PostHog.class);
        NoOpAnalyticsService svc = new NoOpAnalyticsService();

        svc.identify("42", Map.of("account_age_days", 7));

        verifyNoInteractions(client);
    }
}
