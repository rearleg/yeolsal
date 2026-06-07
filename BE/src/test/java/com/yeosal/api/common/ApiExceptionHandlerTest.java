package com.yeosal.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.yeosal.api.room.RoomFullException;

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

    @Test
    @DisplayName("UnauthorizedException maps to 401 + UNAUTHORIZED code with the original message")
    void unauthorized_returns401() {
        ResponseEntity<ApiErrorResponse> response =
                handler.unauthorized(new UnauthorizedException("인증이 필요합니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("UNAUTHORIZED");
        assertThat(err.message()).isEqualTo("인증이 필요합니다.");
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns 500 + INTERNAL_ERROR with sanitized message (root-cause stays in logs only)")
    void dataIntegrity_returns500_sanitized() {
        // The PR I diagnosis path: prod was returning a generic 500 with no
        // signal about which constraint blew up. The new handler logs the
        // root cause for ops while still keeping the response envelope safe.
        SQLException root = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"chat_messages_pkey\"",
                "23505");
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("could not execute statement", root);

        ResponseEntity<ApiErrorResponse> response = handler.dataIntegrity(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("INTERNAL_ERROR");
        // Response message must not leak the SQL string back to the client.
        assertThat(err.message()).doesNotContain("constraint");
        assertThat(err.message()).doesNotContain("chat_messages_pkey");
    }

    @Test
    @DisplayName("RoomFullException maps to 409 CONFLICT + ROOM_FULL code (Story 6.2 — epics:854 wire lock)")
    void roomFull_returns409Conflict_withRoomFullCode() {
        ResponseEntity<ApiErrorResponse> response =
                handler.roomFull(new RoomFullException("방 정원을 초과했습니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("ROOM_FULL");
        assertThat(err.message()).isEqualTo("방 정원을 초과했습니다.");
    }

    @Test
    @DisplayName("IllegalArgumentException maps to 400 + VALIDATION (caller-supplied bad input, not a server bug)")
    void illegalArgument_returns400() {
        // ChatService.parsePayload throws IllegalArgumentException on
        // malformed payloads from internal callers — the leaked 500 in
        // production made it look like a server bug. PR I maps it back
        // to 400 so the FE/Sentry path doesn't classify it as a 5xx.
        IllegalArgumentException ex =
                new IllegalArgumentException("system message payload must be a JSON object");

        ResponseEntity<ApiErrorResponse> response = handler.illegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiErrorResponse.Error err = response.getBody().error();
        assertThat(err.code()).isEqualTo("VALIDATION");
    }
}
