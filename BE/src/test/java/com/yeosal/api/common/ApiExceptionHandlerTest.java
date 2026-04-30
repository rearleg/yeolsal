package com.yeosal.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("LazyInitializationException returns 500 instead of being forwarded to /error (avoids 403 disguise)")
    void lazyInit_returns500() {
        LazyInitializationException ex =
                new LazyInitializationException("could not initialize proxy [com.yeosal.api.room.Room#1] - no Session");

        ResponseEntity<Map<String, String>> response = handler.lazyInit(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsKey("message");
        assertThat(response.getBody().get("message")).doesNotContain("could not initialize proxy");
    }

    @Test
    @DisplayName("Unhandled RuntimeException falls through to 500 with sanitized message")
    void unhandled_runtimeException_returns500() {
        RuntimeException ex = new RuntimeException("internal db error: SELECT users.password_hash FROM users");

        ResponseEntity<Map<String, String>> response = handler.unhandled(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).doesNotContain("password_hash");
    }
}
