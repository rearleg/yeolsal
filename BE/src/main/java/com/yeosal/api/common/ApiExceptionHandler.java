package com.yeosal.api.common;

import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(LazyInitializationException.class)
    public ResponseEntity<ApiErrorResponse> lazyInit(LazyInitializationException exception) {
        log.error("LazyInitializationException leaked out of @Transactional boundary", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unhandled(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE));
    }
}
