package com.yeosal.api.revival;

import com.yeosal.api.common.BadRequestException;

/**
 * Story 3.1 — the survival_state row is not in an eliminated lifecycle
 * status (i.e. status ∉ {RED, SPECTATOR}, or status is RED/SPECTATOR but
 * {@code eliminated_at} is null — a data anomaly the partial unique
 * index cannot defend against). Thrown by {@code RevivalService.reviveSelf}
 * before the advisory lock attempt to short-circuit obviously-invalid
 * revival requests.
 *
 * <p>Subclass of {@link BadRequestException} so the existing
 * {@code ApiExceptionHandler.badRequest} fallback still maps unhandled
 * variants to {@code 400 BAD_REQUEST}; the dedicated handler exists to
 * surface the stable wire code {@link #CODE}.
 */
public class NotEliminatedException extends BadRequestException {
    public static final String CODE = "NOT_ELIMINATED";

    public NotEliminatedException(String message) {
        super(message);
    }
}
