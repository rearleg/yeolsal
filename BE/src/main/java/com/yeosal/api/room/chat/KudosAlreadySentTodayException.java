package com.yeosal.api.room.chat;

/**
 * Story 3.5 — same-day duplicate kudos (sender, target, KST date). The
 * V12 partial unique index {@code ux_kudos_one_per_day} is the dedupe
 * authority; the service-layer catch translates a
 * {@link org.springframework.dao.DataIntegrityViolationException} whose
 * root constraint name matches this index into this typed exception so
 * the wire response is the stable 409 / {@link #CODE} pair (not the
 * generic 500 the {@code dataIntegrity} handler would emit).
 *
 * <p>The {@link #CODE} string is the FE-facing branch key; it MUST stay
 * {@code "KUDOS_ALREADY_SENT_TODAY"} so the Story 3.2 Friend Gift Modal
 * (downstream consumer) can surface "오늘은 이미 응원을 보냈어요" without
 * the FE having to parse a localized Korean message.
 */
public class KudosAlreadySentTodayException extends RuntimeException {

    /** Stable wire code surfaced via {@code ApiErrorResponse.error.code}. */
    public static final String CODE = "KUDOS_ALREADY_SENT_TODAY";

    public KudosAlreadySentTodayException(String message) {
        super(message);
    }
}
