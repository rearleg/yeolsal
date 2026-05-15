package com.yeosal.api.common;

import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * Without this mapping these surface as the generic 500 path, which the FE
     * 5xx branch then reports to Sentry as if it were a server bug.
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
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
