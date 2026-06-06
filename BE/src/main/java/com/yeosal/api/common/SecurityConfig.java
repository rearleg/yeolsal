package com.yeosal.api.common;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/kakao/authorize",
                                "/api/v1/auth/kakao/callback",
                                "/api/v1/auth/kakao/exchange",
                                "/api/v1/auth/refresh"
                        ).permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // WebSocket upgrade endpoint: HTTP layer is open;
                        // auth happens at the STOMP CONNECT frame inside
                        // JwtChannelInterceptor. Without this entry the
                        // handshake never reaches the broker.
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Story 6.1 AC2 — KakaoTalk fetches the preview card
                        // unauthenticated. PNG content is non-PII (room name +
                        // member count + rule summary), GET-only, single
                        // path-segment wildcard so depth cannot drift.
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/*/invites/preview-card").permitAll()
                        .anyRequest().authenticated()
                )
                // Both custom filters anchor against UsernamePasswordAuthenticationFilter
                // because Spring Security's addFilterBefore requires the marker to be a
                // framework filter with a registered order. Anchoring rate-limit against
                // our own JwtAuthenticationFilter throws "does not have a registered
                // order". Filters added at the same offset run in registration order,
                // so rate-limit (registered first) executes before JWT — desirable.
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Profile-aware CORS allowlist. Origins come from
     * {@code yeosal.cors.allowed-origins} (comma-separated). Default is
     * {@code *} for local dev; the prod profile rebinds this to an
     * explicit list via env so the production API does not advertise to
     * arbitrary callers. Auth uses Bearer tokens (no cookies), so
     * credentials are disabled and the wildcard pattern is safe.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${yeosal.cors.allowed-origins:*}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
