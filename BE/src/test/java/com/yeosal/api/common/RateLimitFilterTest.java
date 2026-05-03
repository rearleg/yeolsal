package com.yeosal.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private static final RateLimitFilter.Rule LOGIN_RULE =
            new RateLimitFilter.Rule(HttpMethod.POST, "/api/v1/auth/login", 3, Duration.ofMinutes(1));

    private FilterChain countingChain(AtomicInteger counter) {
        return (req, res) -> counter.incrementAndGet();
    }

    private MockHttpServletRequest postLogin(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setServletPath("/api/v1/auth/login");
        req.setRemoteAddr(ip);
        return req;
    }

    @Test
    @DisplayName("non-matched paths pass through without counting")
    void unmatchedPath_passesThrough() throws Exception {
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), Clock.systemUTC());
        AtomicInteger downstream = new AtomicInteger();

        var req = new MockHttpServletRequest();
        req.setMethod("GET");
        req.setServletPath("/api/v1/profiles/me");
        req.setRemoteAddr("1.2.3.4");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, countingChain(downstream));

        assertThat(downstream.get()).isEqualTo(1);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("blocks N+1th request from same IP within window")
    void exceedsLimit_returns429() throws Exception {
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), Clock.systemUTC());
        AtomicInteger downstream = new AtomicInteger();

        for (int i = 0; i < LOGIN_RULE.permits(); i++) {
            var res = new MockHttpServletResponse();
            filter.doFilter(postLogin("1.2.3.4"), res, countingChain(downstream));
            assertThat(res.getStatus()).isEqualTo(200);
        }
        var blocked = new MockHttpServletResponse();
        filter.doFilter(postLogin("1.2.3.4"), blocked, countingChain(downstream));

        assertThat(downstream.get()).isEqualTo(LOGIN_RULE.permits());
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("요청이 너무 많습니다");
    }

    @Test
    @DisplayName("different IPs have independent buckets")
    void differentIps_independent() throws Exception {
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), Clock.systemUTC());
        AtomicInteger downstream = new AtomicInteger();

        for (int i = 0; i < LOGIN_RULE.permits(); i++) {
            filter.doFilter(postLogin("1.2.3.4"), new MockHttpServletResponse(), countingChain(downstream));
        }
        var fromOtherIp = new MockHttpServletResponse();
        filter.doFilter(postLogin("5.6.7.8"), fromOtherIp, countingChain(downstream));

        assertThat(fromOtherIp.getStatus()).isEqualTo(200);
        assertThat(downstream.get()).isEqualTo(LOGIN_RULE.permits() + 1);
    }

    @Test
    @DisplayName("window resets after duration expires")
    void windowResets() throws Exception {
        var fakeClock = new MutableClock(Instant.parse("2026-05-01T00:00:00Z"));
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), fakeClock);
        AtomicInteger downstream = new AtomicInteger();

        for (int i = 0; i < LOGIN_RULE.permits(); i++) {
            filter.doFilter(postLogin("1.2.3.4"), new MockHttpServletResponse(), countingChain(downstream));
        }

        var blocked = new MockHttpServletResponse();
        filter.doFilter(postLogin("1.2.3.4"), blocked, countingChain(downstream));
        assertThat(blocked.getStatus()).isEqualTo(429);

        fakeClock.advance(LOGIN_RULE.window().plusSeconds(1));

        var afterReset = new MockHttpServletResponse();
        filter.doFilter(postLogin("1.2.3.4"), afterReset, countingChain(downstream));
        assertThat(afterReset.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("X-Forwarded-For first hop is used as client ip")
    void usesXForwardedFor() throws Exception {
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), Clock.systemUTC());
        AtomicInteger downstream = new AtomicInteger();

        for (int i = 0; i < LOGIN_RULE.permits(); i++) {
            var req = postLogin("10.0.0.1");
            req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
            filter.doFilter(req, new MockHttpServletResponse(), countingChain(downstream));
        }

        var blocked = new MockHttpServletResponse();
        var req2 = postLogin("10.0.0.1");
        req2.addHeader("X-Forwarded-For", "203.0.113.5");
        filter.doFilter(req2, blocked, countingChain(downstream));
        assertThat(blocked.getStatus()).isEqualTo(429);

        var differentClient = new MockHttpServletResponse();
        var req3 = postLogin("10.0.0.1");
        req3.addHeader("X-Forwarded-For", "203.0.113.99");
        filter.doFilter(req3, differentClient, countingChain(downstream));
        assertThat(differentClient.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("trustForwardedFor=false: X-Forwarded-For is ignored — same socket address shares the bucket")
    void xffIgnoredWhenUntrusted() throws Exception {
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), Clock.systemUTC(), false);
        AtomicInteger downstream = new AtomicInteger();

        // Three forged X-Forwarded-For values from the same socket address
        // all consume the same bucket because the filter ignores the header
        // and uses request.getRemoteAddr().
        for (int i = 0; i < LOGIN_RULE.permits(); i++) {
            var req = postLogin("10.0.0.1");
            req.addHeader("X-Forwarded-For", "203.0.113." + i);
            filter.doFilter(req, new MockHttpServletResponse(), countingChain(downstream));
        }

        var blocked = new MockHttpServletResponse();
        var req2 = postLogin("10.0.0.1");
        req2.addHeader("X-Forwarded-For", "203.0.113.99");
        filter.doFilter(req2, blocked, countingChain(downstream));
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("trustForwardedFor=false: a different socket address gets its own bucket")
    void untrustedFallsBackToRemoteAddr() throws Exception {
        var filter = new RateLimitFilter(List.of(LOGIN_RULE), Clock.systemUTC(), false);
        AtomicInteger downstream = new AtomicInteger();

        for (int i = 0; i < LOGIN_RULE.permits(); i++) {
            filter.doFilter(postLogin("10.0.0.1"), new MockHttpServletResponse(), countingChain(downstream));
        }

        var sameSource = new MockHttpServletResponse();
        filter.doFilter(postLogin("10.0.0.1"), sameSource, countingChain(downstream));
        assertThat(sameSource.getStatus()).isEqualTo(429);

        var differentSource = new MockHttpServletResponse();
        filter.doFilter(postLogin("10.0.0.2"), differentSource, countingChain(downstream));
        assertThat(differentSource.getStatus()).isEqualTo(200);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
