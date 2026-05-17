package com.yeosal.api.revival;

/**
 * Thrown when a PERSONAL_POINTS revival is attempted with a balance below
 * the 3-point threshold (FR-8.3.2, epics line 445-447). Mapped to
 * {@code 400 BAD_REQUEST} with code {@code INSUFFICIENT_POINTS} by
 * {@code ApiExceptionHandler}. The balance check runs INSIDE the advisory
 * lock so it observes any in-flight concurrent debits (Story 3.2's
 * FRIEND_GIFT_SPEND, room-leave penalties) consistently.
 */
public class InsufficientPointsException extends RuntimeException {
    public InsufficientPointsException(String message) {
        super(message);
    }
}
