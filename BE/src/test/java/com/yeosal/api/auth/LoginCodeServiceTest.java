package com.yeosal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginCodeServiceTest {

    private final Instant now = Instant.parse("2026-05-01T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

    private LoginCodeRepository repo;
    private LoginCodeService service;
    private User user;

    @BeforeEach
    void setUp() {
        repo = mock(LoginCodeRepository.class);
        service = new LoginCodeService(repo, new DeterministicRandom(), fixedClock);
        user = new User("alice@example.com", "alice", null, AuthProvider.KAKAO);

        // make repo.save return its argument so we can assert on it
        when(repo.save(any(LoginCode.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("issue: returns Base64URL-encoded 32-byte code without padding")
    void issue_returnsCodeAndPersists() {
        String code = service.issue(user, true);

        // Base64URL of 32 bytes (no padding) = 43 chars
        assertThat(code).hasSize(43);
        // No padding, no '+' or '/' (Base64URL alphabet)
        assertThat(code).doesNotContain("=", "+", "/");
    }

    @Test
    @DisplayName("exchange: returns user and marks consumed when valid")
    void exchange_validCode_returnsUser() {
        LoginCode lc = new LoginCode("abc", user, now.plus(Duration.ofMinutes(1)), true);
        when(repo.findByCodeAndConsumedAtIsNull("abc")).thenReturn(Optional.of(lc));

        LoginCodeService.ExchangeResult result = service.exchange("abc");

        assertThat(result.user()).isSameAs(user);
        assertThat(result.newAccount()).isTrue();
        assertThat(lc.getConsumedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("exchange: throws when code unknown")
    void exchange_unknownCode_throws() {
        when(repo.findByCodeAndConsumedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("nope"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid login code");
    }

    @Test
    @DisplayName("exchange: throws when code already consumed (repo returns empty)")
    void exchange_consumedCode_throws() {
        // findByCodeAndConsumedAtIsNull skips consumed rows by definition
        when(repo.findByCodeAndConsumedAtIsNull("used")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("used"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid login code");
    }

    @Test
    @DisplayName("exchange: throws when code expired and does not consume")
    void exchange_expiredCode_throws() {
        LoginCode expired = new LoginCode("old", user, now.minus(Duration.ofSeconds(1)), false);
        when(repo.findByCodeAndConsumedAtIsNull("old")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.exchange("old"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid login code");

        assertThat(expired.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("exchange: throws on null or blank code without DB lookup")
    void exchange_blankCode_throws() {
        assertThatThrownBy(() -> service.exchange(null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.exchange("  "))
                .isInstanceOf(BadRequestException.class);
    }

    /** Deterministic SecureRandom replacement so test output is stable. */
    private static final class DeterministicRandom extends SecureRandom {
        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i + 1);
            }
        }
    }
}
