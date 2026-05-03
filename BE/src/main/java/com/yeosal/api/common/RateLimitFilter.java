package com.yeosal.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In-memory fixed-window rate limiter for sensitive auth and abuse-prone
 * endpoints. Keyed by (rule, client IP). Single-instance scope; multi-node
 * deployments will need to swap the storage for Redis but the rule list
 * and the integration point stay the same.
 *
 * <p>Fixed-window has a known boundary-doubling case (limit*2 within a
 * sub-second window straddling a reset). For brute-force prevention on
 * login/refresh/invite endpoints this is acceptable, since the absolute
 * cap is still hundreds of orders of magnitude below what the JWT/BCrypt
 * pipeline can survive.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final List<Rule> DEFAULT_RULES = List.of(
            new Rule(HttpMethod.POST, "/api/v1/auth/login", 10, Duration.ofMinutes(1)),
            new Rule(HttpMethod.POST, "/api/v1/auth/signup", 5, Duration.ofMinutes(1)),
            new Rule(HttpMethod.POST, "/api/v1/auth/refresh", 30, Duration.ofMinutes(1)),
            new Rule(HttpMethod.POST, "/api/v1/auth/kakao/exchange", 10, Duration.ofMinutes(1)),
            new Rule(HttpMethod.POST, "/api/v1/friends/requests", 20, Duration.ofMinutes(1)),
            new Rule(HttpMethod.POST, "/api/v1/rooms/join", 10, Duration.ofMinutes(1)),
            new Rule(HttpMethod.POST, "/api/v1/rooms/*/invites", 20, Duration.ofMinutes(1)),
            // 60 messages / minute per IP. Single-instance scope; multi-node
            // deployments will need to swap the storage for a per-user Redis
            // counter. Keep the limit comfortably above normal chat speed.
            new Rule(HttpMethod.POST, "/api/v1/rooms/*/messages", 60, Duration.ofMinutes(1))
    );

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final List<Rule> rules;
    private final Clock clock;
    private final boolean trustForwardedFor;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Production-time autowiring entry point. Spring's resolution falls
     * back to the no-arg constructor when several constructors exist and
     * none is annotated, so we anchor the prod ctor explicitly to keep
     * the bean factory from rejecting the filter at boot time.
     */
    @Autowired
    public RateLimitFilter(
            @Value("${yeosal.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this(DEFAULT_RULES, Clock.systemUTC(), trustForwardedFor);
    }

    /** Test ctor — keeps the legacy 2-arg shape with X-Forwarded-For trust ON
     *  so existing tests that pre-date the toggle keep their behaviour. */
    RateLimitFilter(List<Rule> rules, Clock clock) {
        this(rules, clock, true);
    }

    RateLimitFilter(List<Rule> rules, Clock clock, boolean trustForwardedFor) {
        this.rules = rules;
        this.clock = clock;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        Rule matched = matchRule(request);
        if (matched == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = matched.method() + " " + matched.path() + "|" + clientIp(request);
        if (!tryAcquire(key, matched)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다.\"}}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Rule matchRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        for (Rule rule : rules) {
            if (rule.method().matches(method) && pathMatcher.match(rule.path(), path)) {
                return rule;
            }
        }
        return null;
    }

    private boolean tryAcquire(String key, Rule rule) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || !existing.resetAt().isAfter(now)) {
                return new Window(now.plus(rule.window()), new AtomicInteger(0));
            }
            return existing;
        });
        return window.count().incrementAndGet() <= rule.permits();
    }

    private String clientIp(HttpServletRequest request) {
        // Trusting X-Forwarded-For only makes sense when the deployment sits
        // behind a proxy that strips and re-sets the header. A directly-
        // exposed instance would let anyone spoof their rate-limit identity
        // by sending a forged header, so we gate it behind the configuration
        // flag and default to the raw socket address.
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    public record Rule(HttpMethod method, String path, int permits, Duration window) {}

    record Window(Instant resetAt, AtomicInteger count) {}
}
