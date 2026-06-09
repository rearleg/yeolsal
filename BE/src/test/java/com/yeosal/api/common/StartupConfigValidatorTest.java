package com.yeosal.api.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class StartupConfigValidatorTest {

    private static final String VALID_SECRET_32 = "01234567890123456789012345678901"; // 32 ASCII bytes
    private static final String VALID_PROD_USER = "yeosal_prod_user";
    private static final String VALID_PROD_PASSWORD = "s3cret-from-vault";
    private static final String VALID_KAKAO_ID = "real-kakao-app-id";

    private Environment devEnv() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        return env;
    }

    private Environment prodEnv() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        return env;
    }

    /**
     * Story 8.5 BE-6 extension — wraps the new 8-arg constructor with
     * the analytics inputs zeroed so the pre-Story-8.5 cases keep their
     * original 5-arg shape semantically. New analytics-specific cases
     * call the 8-arg constructor directly via {@link #withAnalytics}.
     */
    private static StartupConfigValidator validator(
            Environment env, String jwtSecret, String dsUser, String dsPass, String kakaoId) {
        return new StartupConfigValidator(
                env, jwtSecret, dsUser, dsPass, kakaoId,
                false, "", "");
    }

    private static StartupConfigValidator withAnalytics(
            Environment env,
            boolean analyticsEnabled,
            String analyticsHost,
            String analyticsKey) {
        return new StartupConfigValidator(
                env, VALID_SECRET_32, VALID_PROD_USER, VALID_PROD_PASSWORD, VALID_KAKAO_ID,
                analyticsEnabled, analyticsHost, analyticsKey);
    }

    @Nested
    @DisplayName("JWT secret validation (all profiles)")
    class JwtSecret {

        @Test
        @DisplayName("blank secret fails")
        void blankSecret_fails() {
            StartupConfigValidator v = validator(devEnv(), "", "u", "p", "k");
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("YEOSAL_JWT_SECRET");
        }

        @Test
        @DisplayName("secret shorter than 32 bytes fails")
        void shortSecret_fails() {
            StartupConfigValidator v = validator(devEnv(), "tooshort", "u", "p", "k");
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 bytes");
        }

        @Test
        @DisplayName("dev placeholder allowed in dev profile")
        void devPlaceholder_allowedInDev() {
            StartupConfigValidator v = validator(
                    devEnv(),
                    StartupConfigValidator.DEV_JWT_SECRET_PLACEHOLDER,
                    "yeosal-dev-only", "yeosal-dev-only", "");
            assertThatCode(v::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("dev placeholder rejected in prod profile")
        void devPlaceholder_rejectedInProd() {
            StartupConfigValidator v = validator(
                    prodEnv(),
                    StartupConfigValidator.DEV_JWT_SECRET_PLACEHOLDER,
                    VALID_PROD_USER, VALID_PROD_PASSWORD, VALID_KAKAO_ID);
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev placeholder");
        }
    }

    @Nested
    @DisplayName("Datasource validation (prod only)")
    class Datasource {

        @Test
        @DisplayName("dev username 'yeosal' rejected in prod")
        void devUsername_rejectedInProd() {
            StartupConfigValidator v = validator(
                    prodEnv(), VALID_SECRET_32, "yeosal", VALID_PROD_PASSWORD, VALID_KAKAO_ID);
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
        }

        @Test
        @DisplayName("dev placeholder username 'yeosal-dev-only' rejected in prod")
        void devPlaceholderUsername_rejectedInProd() {
            StartupConfigValidator v = validator(
                    prodEnv(), VALID_SECRET_32, "yeosal-dev-only", VALID_PROD_PASSWORD, VALID_KAKAO_ID);
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
        }

        @Test
        @DisplayName("dev password 'yeosal' rejected in prod")
        void devPassword_rejectedInProd() {
            StartupConfigValidator v = validator(
                    prodEnv(), VALID_SECRET_32, VALID_PROD_USER, "yeosal", VALID_KAKAO_ID);
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
        }

        @Test
        @DisplayName("blank password rejected in prod")
        void blankPassword_rejectedInProd() {
            StartupConfigValidator v = validator(
                    prodEnv(), VALID_SECRET_32, VALID_PROD_USER, "", VALID_KAKAO_ID);
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
        }

        @Test
        @DisplayName("dev creds allowed in dev profile")
        void devCreds_allowedInDev() {
            StartupConfigValidator v = validator(
                    devEnv(), VALID_SECRET_32, "yeosal", "yeosal", "");
            assertThatCode(v::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Kakao client-id validation (prod only)")
    class Kakao {

        @Test
        @DisplayName("blank client-id rejected in prod")
        void blankClientId_rejectedInProd() {
            StartupConfigValidator v = validator(
                    prodEnv(), VALID_SECRET_32, VALID_PROD_USER, VALID_PROD_PASSWORD, "");
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KAKAO_CLIENT_ID");
        }

        @Test
        @DisplayName("blank client-id allowed in dev")
        void blankClientId_allowedInDev() {
            StartupConfigValidator v = validator(
                    devEnv(), VALID_SECRET_32, "yeosal-dev-only", "yeosal-dev-only", "");
            assertThatCode(v::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Story 8.5 — analytics-config consistency (all profiles)")
    class AnalyticsConsistency {

        @Test
        @DisplayName("enabled=true with blank POSTHOG_HOST rejected")
        void enabledWithBlankHost_rejected() {
            StartupConfigValidator v = withAnalytics(prodEnv(), true, "", "phc_real_key");
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("POSTHOG_HOST");
        }

        @Test
        @DisplayName("enabled=true with blank POSTHOG_PROJECT_API_KEY rejected")
        void enabledWithBlankApiKey_rejected() {
            StartupConfigValidator v = withAnalytics(
                    prodEnv(), true, "https://analytics.example.com", "");
            assertThatThrownBy(v::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("POSTHOG_PROJECT_API_KEY");
        }

        @Test
        @DisplayName("enabled=false with everything blank is OK (dev / OSS forks)")
        void disabledWithBlankInputs_ok() {
            StartupConfigValidator v = withAnalytics(prodEnv(), false, "", "");
            assertThatCode(v::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("enabled=true with both populated is OK")
        void enabledFullyConfigured_ok() {
            StartupConfigValidator v = withAnalytics(
                    prodEnv(), true, "https://analytics.example.com", "phc_real_key");
            assertThatCode(v::validate).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("fully valid prod config passes")
    void validProd_passes() {
        StartupConfigValidator v = validator(
                prodEnv(), VALID_SECRET_32, VALID_PROD_USER, VALID_PROD_PASSWORD, VALID_KAKAO_ID);
        assertThatCode(v::validate).doesNotThrowAnyException();
    }
}
