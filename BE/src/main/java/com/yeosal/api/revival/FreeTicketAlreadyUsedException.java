package com.yeosal.api.revival;

import com.yeosal.api.common.BadRequestException;

/**
 * Story 3.1 — the user's lifetime-one free revival ticket has already
 * been consumed (Architecture §4.12). Thrown when
 * {@code UserRepository.markFreeTicketUsed} updates zero rows — a
 * parallel session in another room or another tab raced this one and
 * already flipped the flag.
 *
 * <p>Subclass of {@link BadRequestException} so the existing
 * {@code ApiExceptionHandler.badRequest} fallback still maps unhandled
 * variants to {@code 400 BAD_REQUEST}; the dedicated handler exists to
 * surface the stable wire code {@link #CODE}.
 */
public class FreeTicketAlreadyUsedException extends BadRequestException {
    public static final String CODE = "FREE_TICKET_ALREADY_USED";

    public FreeTicketAlreadyUsedException(String message) {
        super(message);
    }
}
