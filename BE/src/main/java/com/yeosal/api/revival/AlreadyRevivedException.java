package com.yeosal.api.revival;

/**
 * Thrown when a revival attempt loses the advisory-lock race or the
 * partial-unique-index conflict path (Architecture §4.4). Mapped to
 * {@code 409 CONFLICT} with code {@code ALREADY_REVIVED} by
 * {@code ApiExceptionHandler}.
 */
public class AlreadyRevivedException extends RuntimeException {
    public AlreadyRevivedException(String message) {
        super(message);
    }
}
