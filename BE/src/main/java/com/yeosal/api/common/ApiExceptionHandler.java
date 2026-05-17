package com.yeosal.api.common;

import com.yeosal.api.revival.AlreadyRevivedException;
import com.yeosal.api.revival.FreeTicketAlreadyUsedException;
import com.yeosal.api.revival.InsufficientPointsException;
import com.yeosal.api.revival.NotEliminatedException;
import com.yeosal.api.room.chat.KudosAlreadySentTodayException;
import com.yeosal.api.room.chat.KudosTargetNotEligibleException;
import com.yeosal.api.room.chat.NotFriendsException;
import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain exceptions to the standardized {@link ApiErrorResponse}
 * envelope. Codes are stable enum strings; messages are human-readable
 * Korean strings the FE may surface verbatim. The HTTP status is the
 * authoritative dimension — clients should branch on status first and
 * fall through to {@code error.code} for finer-grained UX.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_MESSAGE = "내부 오류가 발생했습니다.";

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiErrorResponse> badRequest(BadRequestException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("BAD_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiErrorResponse> unauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("UNAUTHORIZED", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("NOT_FOUND", exception.getMessage()));
    }

    /**
     * Story 2.1 AC4 — surface the {@code SpectatorWriteForbiddenException}
     * subtype with a stable wire code so the FE can distinguish "spectator
     * cannot post" from generic 403s (room-not-member, etc.). Placed above
     * the generic {@link #forbidden(ForbiddenException)} handler for human
     * readability — Spring resolves the most specific subtype first
     * regardless of source order, but proximity to the parent makes the
     * intent obvious during review.
     */
    @ExceptionHandler(SpectatorWriteForbiddenException.class)
    ResponseEntity<ApiErrorResponse> spectatorWriteForbidden(SpectatorWriteForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(SpectatorWriteForbiddenException.CODE, exception.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiErrorResponse> forbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("FORBIDDEN", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("VALIDATION", "잘못된 요청입니다."));
    }

    /**
     * Missing or unparseable @RequestParam (e.g. {@code ?cursor=not-a-number}).
     * Story 3.1 review finding 3 — {@link HttpMessageNotReadableException}
     * captures Jackson enum-binding failures on @RequestBody DTOs (e.g.
     * `{ "source": "INVALID" }` on the revival endpoint) so they surface as
     * {@code 400 VALIDATION} instead of falling through to the generic 500
     * handler.
     *
     * <p>Without this mapping these surface as the generic 500 path, which
     * the FE 5xx branch then reports to Sentry as if it were a server bug.
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiErrorResponse> requestParamValidation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("VALIDATION", "잘못된 요청입니다."));
    }

    @ExceptionHandler(LazyInitializationException.class)
    public ResponseEntity<ApiErrorResponse> lazyInit(LazyInitializationException exception) {
        log.error("LazyInitializationException leaked out of @Transactional boundary", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE));
    }

    /**
     * DB-side integrity violation (unique key, FK, CHECK, jsonb cast). The
     * generic {@link #unhandled} handler used to swallow these as a faceless
     * 500, which made production diagnosis painful — Bug #2 in plan PR I
     * ("내부 오류가 발생했습니다") was a representative example. Now we log
     * the root cause class + message at WARN so ops can see *which*
     * constraint blew up, while keeping the response envelope sanitized.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> dataIntegrity(DataIntegrityViolationException exception) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(exception);
        log.warn("[db] data integrity violation root={} message={}",
                root.getClass().getSimpleName(), root.getMessage());
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE));
    }

    /**
     * Internal callers (e.g. {@code ChatService.parsePayload}) throw
     * {@link IllegalArgumentException} for malformed inputs that the
     * controller layer should already have rejected. Mapping these to 400
     * VALIDATION keeps them out of the FE 5xx branch / Sentry server-bug
     * channel where they don't belong.
     */
    /**
     * Story 3.1 — revival lost the advisory-lock race or the partial-unique
     * index conflict path. Architecture §4.4 — the service layer catches
     * the {@link DataIntegrityViolationException} from the secondary
     * defence and rethrows as this typed exception so the generic
     * {@link #dataIntegrity} handler stays out of the revival contract.
     */
    @ExceptionHandler(AlreadyRevivedException.class)
    ResponseEntity<ApiErrorResponse> alreadyRevived(AlreadyRevivedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("ALREADY_REVIVED", exception.getMessage()));
    }

    /**
     * Story 3.1 — PERSONAL_POINTS revival attempted with balance below the
     * 3-point threshold. The balance check runs inside the advisory lock
     * so the response reflects any in-flight concurrent debits
     * consistently (FR-8.3.2, epics 445-447).
     */
    @ExceptionHandler(InsufficientPointsException.class)
    ResponseEntity<ApiErrorResponse> insufficientPoints(InsufficientPointsException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("INSUFFICIENT_POINTS", exception.getMessage()));
    }

    /**
     * Story 3.1 — FREE_TICKET revival attempted but the user's lifetime-one
     * flag is already consumed (parallel session in another room/tab won
     * the atomic check-and-set). Typed subclass of
     * {@link BadRequestException} keeps the wire code stable
     * ({@link FreeTicketAlreadyUsedException#CODE}).
     */
    @ExceptionHandler(FreeTicketAlreadyUsedException.class)
    ResponseEntity<ApiErrorResponse> freeTicketAlreadyUsed(FreeTicketAlreadyUsedException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(FreeTicketAlreadyUsedException.CODE, exception.getMessage()));
    }

    /**
     * Story 3.1 — revival attempted on a survival_state row whose status
     * isn't an eliminated lifecycle phase (status ∉ {RED, SPECTATOR}, or
     * RED/SPECTATOR with null {@code eliminated_at}). Stable wire code
     * lets the FE surface a precise toast rather than a generic 400.
     */
    @ExceptionHandler(NotEliminatedException.class)
    ResponseEntity<ApiErrorResponse> notEliminated(NotEliminatedException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(NotEliminatedException.CODE, exception.getMessage()));
    }

    /**
     * Story 3.5 — same-day duplicate kudos collapsed by the V12 partial
     * unique index {@code ux_kudos_one_per_day}. The service layer
     * translates the {@link org.springframework.dao.DataIntegrityViolationException}
     * caught at the INSERT site into this typed exception so the 409 /
     * {@link KudosAlreadySentTodayException#CODE} pair is wire-stable
     * (the generic {@link #dataIntegrity} handler stays out of the kudos
     * contract).
     */
    @ExceptionHandler(KudosAlreadySentTodayException.class)
    ResponseEntity<ApiErrorResponse> kudosAlreadySentToday(KudosAlreadySentTodayException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(KudosAlreadySentTodayException.CODE, exception.getMessage()));
    }

    /**
     * Story 3.5 — kudos target's {@code survival_state.status} is not in
     * the eligible {@code {RED, SPECTATOR}} set. Surfaces as 400 /
     * {@link KudosTargetNotEligibleException#CODE} so the Friend Gift Modal
     * (Story 3.2 downstream) can show "이 친구는 지금 응원 대상이 아니에요"
     * without parsing the localized message.
     */
    @ExceptionHandler(KudosTargetNotEligibleException.class)
    ResponseEntity<ApiErrorResponse> kudosTargetNotEligible(KudosTargetNotEligibleException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(KudosTargetNotEligibleException.CODE, exception.getMessage()));
    }

    /**
     * Story 3.5 — kudos send attempted between two users without an
     * {@code ACCEPTED} friendship row. Mirrors the
     * {@link #spectatorWriteForbidden} placement: above the generic
     * {@link #forbidden} handler so Spring's most-specific-subtype
     * resolution surfaces the precise 403 / {@link NotFriendsException#CODE}
     * pair rather than the generic {@code "FORBIDDEN"} bucket.
     */
    @ExceptionHandler(NotFriendsException.class)
    ResponseEntity<ApiErrorResponse> notFriends(NotFriendsException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(NotFriendsException.CODE, exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> illegalArgument(IllegalArgumentException exception) {
        log.warn("[validation] caller-supplied IllegalArgumentException: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("VALIDATION", "잘못된 요청입니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unhandled(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE));
    }
}
