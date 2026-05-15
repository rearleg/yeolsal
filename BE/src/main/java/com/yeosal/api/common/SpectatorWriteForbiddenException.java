package com.yeosal.api.common;

/**
 * Story 2.1 — thrown by {@code ChatService.sendUserMessage} when the
 * caller's {@code survival_state.status} for the target room is
 * {@code SPECTATOR}. The FE input is hidden in parallel (NFR-9.2.5 double
 * enforcement); this exception is the BE half of the gate so a buggy or
 * tampered client cannot post.
 *
 * <p>Extends {@link ForbiddenException} so existing 403 callers keep
 * working when narrowing only to the parent. The {@link #CODE} string is
 * the stable machine-readable enum the FE branches on — it MUST stay
 * "{@code SPECTATOR_WRITE_FORBIDDEN}" so client code does not silently
 * fall back to the generic {@code "FORBIDDEN"} bucket.
 */
public class SpectatorWriteForbiddenException extends ForbiddenException {

    /** Stable wire code surfaced via {@code ApiErrorResponse.error.code}. */
    public static final String CODE = "SPECTATOR_WRITE_FORBIDDEN";

    public SpectatorWriteForbiddenException() {
        super("관전 중에는 메시지를 보낼 수 없어요.");
    }
}
