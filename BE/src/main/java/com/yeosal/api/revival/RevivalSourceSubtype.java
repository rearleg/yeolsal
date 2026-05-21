package com.yeosal.api.revival;

/**
 * Story 3.3 AC3 — discriminator stamped on FRIEND_GIFT revival rows so the
 * Day-30 falsification test (Architecture §4.8) can compare push-driven
 * versus wallet-badge-driven friend-gifts via {@code revival_events.source_subtype}.
 *
 * <p>The V11 column ships as {@code varchar(20)} without a CHECK
 * constraint (Architecture §6.3 V11 line 50). Enforcement lives at the
 * Java boundary here, mirroring {@link LedgerReason} / {@link RevivalSource}
 * precedents — additive future values (e.g. {@code DIGEST_INITIATED}) can
 * be added without a Flyway migration.
 *
 * <p>Self-revival rows persist {@code null} for {@code source_subtype}
 * (see {@link RevivalService#reviveSelf}); only friend-gift rows carry a
 * non-null subtype. The default-when-null mapping for friend-gift requests
 * is {@link #PUSH_INITIATED} (backward compat with Story 3.2 callers that
 * omit the field).
 */
public enum RevivalSourceSubtype {
    /** Friend-gift initiated from the push-tap deep-link flow (Story 3.2). */
    PUSH_INITIATED,
    /** Friend-gift initiated from the passive wallet-badge flow (Story 3.3). */
    WALLET_INITIATED
}
