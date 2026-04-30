package com.yeosal.api.common;

import java.util.Map;
import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_MESSAGE = "내부 오류가 발생했습니다.";

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, String>> badRequest(BadRequestException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid request"));
    }

    @ExceptionHandler(LazyInitializationException.class)
    public ResponseEntity<Map<String, String>> lazyInit(LazyInitializationException exception) {
        log.error("LazyInitializationException leaked out of @Transactional boundary", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", INTERNAL_ERROR_MESSAGE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unhandled(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", INTERNAL_ERROR_MESSAGE));
    }
}
