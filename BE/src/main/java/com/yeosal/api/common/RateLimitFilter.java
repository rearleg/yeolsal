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
            new Rule(HttpMethod.POST, "/api/v1/rooms/*/invites", 20, Duration.ofMinutes(1))
    );

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final List<Rule> rules;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter() {
        this(DEFAULT_RULES, Clock.systemUTC());
    }

    RateLimitFilter(List<Rule> rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
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
            response.getWriter().write("{\"message\":\"요청이 너무 많습니다.\"}");
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

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record Rule(HttpMethod method, String path, int permits, Duration window) {}

    record Window(Instant resetAt, AtomicInteger count) {}
}
