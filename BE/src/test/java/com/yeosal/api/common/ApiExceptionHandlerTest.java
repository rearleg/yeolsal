package com.yeosal.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("LazyInitializationException returns 500 with INTERNAL_ERROR code and a sanitized message")
    void lazyInit_returns500() {
        LazyInitializationException ex =
                new LazyInitializationException("could not initialize proxy [com.yeosal.api.room.Room#1] - no Session");

        ResponseEntity<ApiErrorResponse> response = handler.lazyInit(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(err.message()).doesNotContain("could not initialize proxy");
    }

    @Test
    @DisplayName("Unhandled RuntimeException falls through to 500 with sanitized message")
    void unhandled_runtimeException_returns500() {
        RuntimeException ex = new RuntimeException("internal db error: SELECT users.password_hash FROM users");

        ResponseEntity<ApiErrorResponse> response = handler.unhandled(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(err.message()).doesNotContain("password_hash");
    }

    @Test
    @DisplayName("BadRequestException maps to 400 + BAD_REQUEST code with the original message")
    void badRequest_returns400() {
        ResponseEntity<ApiErrorResponse> response =
                handler.badRequest(new BadRequestException("이메일 형식이 잘못되었습니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("BAD_REQUEST");
        assertThat(err.message()).isEqualTo("이메일 형식이 잘못되었습니다.");
    }

    @Test
    @DisplayName("NotFoundException maps to 404 + NOT_FOUND code")
    void notFound_returns404() {
        ResponseEntity<ApiErrorResponse> response = handler.notFound(new NotFoundException("not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("ForbiddenException maps to 403 + FORBIDDEN code")
    void forbidden_returns403() {
        ResponseEntity<ApiErrorResponse> response = handler.forbidden(new ForbiddenException("nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error().code()).isEqualTo("FORBIDDEN");
    }
}
