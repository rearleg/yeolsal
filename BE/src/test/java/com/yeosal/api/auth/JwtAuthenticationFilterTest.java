package com.yeosal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtAuthenticationFilterTest {

    @Test
    @DisplayName("filter runs on ERROR dispatch so SecurityContext is populated when handler forwards to /error")
    void runsOnErrorDispatch() {
        TestableFilter filter = new TestableFilter(mock(JwtService.class));

        assertThat(filter.exposeShouldNotFilterErrorDispatch()).isFalse();
    }

    private static final class TestableFilter extends JwtAuthenticationFilter {
        TestableFilter(JwtService jwtService) {
            super(jwtService);
        }

        boolean exposeShouldNotFilterErrorDispatch() {
            return shouldNotFilterErrorDispatch();
        }
    }
}
