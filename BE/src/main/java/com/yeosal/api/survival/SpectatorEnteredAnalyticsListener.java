package com.yeosal.api.survival;

import com.yeosal.api.analytics.AnalyticsService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emits the {@code spectator.entered} analytics event (docs/analytics.md
 * Funnel 4 — Spectator → revival cohort) when a member transitions to
 * {@link SurvivalStatus#RED}.
 *
 * <p>v1 modelling note: the literal {@link SurvivalStatus#SPECTATOR} value
 * is never written — the {@code SurvivalStateService} evaluator only
 * produces YELLOW and RED. Per the taxonomy, "entering the spectator
 * cohort" <em>is</em> the RED elimination itself (an eliminated member
 * spectates their room until they revive or the month ends), so this
 * listener keys on the RED transition, exactly as docs/analytics.md
 * specifies ("SurvivalStateService ACTIVE/YELLOW → RED transition").
 *
 * <p>Rides the same {@link SurvivalStateTransitionEvent} AFTER_COMMIT seam
 * as {@code EligibleGiverPushListener} and {@code AutoLeaderPromotionListener}
 * so a rolled-back elimination never emits. No {@code @Transactional} is
 * needed: the body performs no DB write, only a swallow-safe analytics
 * capture (the {@link AnalyticsService} implementations never throw).
 */
@Component
public class SpectatorEnteredAnalyticsListener {

    private final AnalyticsService analyticsService;

    public SpectatorEnteredAnalyticsListener(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(SurvivalStateTransitionEvent event) {
        if (event.toStatus() != SurvivalStatus.RED) {
            return;
        }
        analyticsService.capture(
                String.valueOf(event.userId()),
                "spectator.entered",
                Map.of(
                        "roomId", event.roomId(),
                        "eliminatedAt", event.occurredAt().toString()));
    }
}
