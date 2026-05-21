package com.yeosal.api.revival;

import com.yeosal.api.common.BadRequestException;

/**
 * Story 3.2 — friend-gift attempted with personal-points balance below the
 * 5-point threshold (FR-8.3.3, epics line 489-491).
 *
 * <p>Distinct exception class from {@link InsufficientPointsException}
 * because the wire codes diverge: {@code INSUFFICIENT_POINTS} is locked to
 * the 3-point self-revival path (Story 3.1) and {@code INSUFFICIENT_GIFT_POINTS}
 * surfaces the 5-point friend-gift path so the FE can show the precise
 * threshold in its toast / disabled-state copy.
 *
 * <p>Extends {@link BadRequestException} so the existing
 * {@code ApiExceptionHandler.badRequest} fallback still maps unhandled
 * variants to 400; the dedicated handler exists to surface the stable
 * wire code {@link #CODE}.
 */
public class InsufficientGiftPointsException extends BadRequestException {

    /** Stable wire code surfaced via {@code ApiErrorResponse.error.code}. */
    public static final String CODE = "INSUFFICIENT_GIFT_POINTS";

    public InsufficientGiftPointsException(String message) {
        super(message);
    }
}
