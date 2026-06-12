package com.yeosal.api.survival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.yeosal.api.analytics.AnalyticsService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Funnel 4 — {@code spectator.entered} emits on the RED transition (the v1
 * spectator-cohort entry); all other transitions are ignored.
 */
@ExtendWith(MockitoExtension.class)
class SpectatorEnteredAnalyticsListenerTest {

    private static final long ROOM_ID = 42L;
    private static final long USER_ID = 11L;
    private static final long OWNER_ID = 7L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-06-12T21:00:00Z");

    @Mock private AnalyticsService analyticsService;

    private SpectatorEnteredAnalyticsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SpectatorEnteredAnalyticsListener(analyticsService);
    }

    @Test
    @DisplayName("RED transition → captures spectator.entered with roomId + eliminatedAt")
    void onTransition_red_capturesSpectatorEntered() {
        listener.onTransition(transition(SurvivalStatus.YELLOW, SurvivalStatus.RED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propsCap = ArgumentCaptor.forClass(Map.class);
        verify(analyticsService, times(1))
                .capture(eq(String.valueOf(USER_ID)), eq("spectator.entered"), propsCap.capture());
        Map<String, Object> props = propsCap.getValue();
        org.assertj.core.api.Assertions.assertThat(props)
                .containsEntry("roomId", ROOM_ID)
                .containsEntry("eliminatedAt", OCCURRED_AT.toString());
    }

    @Test
    @DisplayName("YELLOW transition → no spectator.entered emit")
    void onTransition_yellow_noEmit() {
        listener.onTransition(transition(SurvivalStatus.ACTIVE, SurvivalStatus.YELLOW));

        verify(analyticsService, never()).capture(any(), any(), any());
    }

    private SurvivalStateTransitionEvent transition(SurvivalStatus from, SurvivalStatus to) {
        Instant broadVisibilityAt = to == SurvivalStatus.RED ? OCCURRED_AT.plusSeconds(86_400) : null;
        return new SurvivalStateTransitionEvent(
                ROOM_ID, USER_ID, OWNER_ID, from, to, OCCURRED_AT, broadVisibilityAt);
    }
}
