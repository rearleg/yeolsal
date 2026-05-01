package com.yeosal.api.common;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast validator for sensitive configuration. Spring runs {@link #validate()}
 * during bean initialization; an {@link IllegalStateException} aborts context
 * startup before any HTTP traffic is served.
 *
 * <p>Two layers of protection:
 * <ol>
 *   <li>{@code application-prod.yml} drops the dev fallback for every required
 *       env var, so a missing env var alone fails Spring property resolution.</li>
 *   <li>This validator additionally rejects values that <em>look</em> dev-only
 *       (e.g. the placeholder JWT secret or the {@code yeosal-dev-only}
 *       datasource credentials) even when an env var is explicitly set, since
 *       those would still be operationally unsafe.</li>
 * </ol>
 */
@Component
public class StartupConfigValidator {

    static final String DEV_JWT_SECRET_PLACEHOLDER = "dev-only-change-me-dev-only-change-me";
    static final int MIN_JWT_SECRET_BYTES = 32;
    static final Set<String> DEV_DATASOURCE_DEFAULTS = Set.of("yeosal-dev-only", "yeosal");
    static final String PROD_PROFILE = "prod";

    private final Environment environment;
    private final String jwtSecret;
    private final String datasourceUsername;
    private final String datasourcePassword;
    private final String kakaoClientId;

    public StartupConfigValidator(
            Environment environment,
            @Value("${yeosal.auth.jwt-secret:}") String jwtSecret,
            @Value("${spring.datasource.username:}") String datasourceUsername,
            @Value("${spring.datasource.password:}") String datasourcePassword,
            @Value("${yeosal.kakao.client-id:}") String kakaoClientId
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword;
        this.kakaoClientId = kakaoClientId;
    }

    @PostConstruct
    void validate() {
        boolean prod = isProdProfile();
        validateJwtSecret(prod);
        if (prod) {
            validateProdDatasource();
            validateProdKakao();
        }
    }

    private boolean isProdProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    }

    private void validateJwtSecret(boolean prod) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "yeosal.auth.jwt-secret (YEOSAL_JWT_SECRET) must be set");
        }
        int bytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "YEOSAL_JWT_SECRET must be at least "
                            + MIN_JWT_SECRET_BYTES
                            + " bytes (got " + bytes + ")");
        }
        if (prod && DEV_JWT_SECRET_PLACEHOLDER.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "YEOSAL_JWT_SECRET is set to the dev placeholder; "
                            + "prod profile requires a real secret");
        }
    }

    private void validateProdDatasource() {
        if (datasourceUsername == null || datasourceUsername.isBlank()
                || DEV_DATASOURCE_DEFAULTS.contains(datasourceUsername)) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_USERNAME must be set to a non-dev value in prod");
        }
        if (datasourcePassword == null || datasourcePassword.isBlank()
                || DEV_DATASOURCE_DEFAULTS.contains(datasourcePassword)) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_PASSWORD must be set to a non-dev value in prod");
        }
    }

    private void validateProdKakao() {
        if (kakaoClientId == null || kakaoClientId.isBlank()) {
            throw new IllegalStateException(
                    "KAKAO_CLIENT_ID must be set in prod (or remove the prod profile if Kakao is disabled)");
        }
    }
}
