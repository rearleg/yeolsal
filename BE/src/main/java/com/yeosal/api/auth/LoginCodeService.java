package com.yeosal.api.auth;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.user.User;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes single-use login codes for the Kakao OAuth deep-link
 * handoff. The Kakao callback endpoint creates a code bound to a user and
 * redirects the mobile app with that code in the deep-link query. The app
 * exchanges the code for real JWT tokens via {@code POST /auth/kakao/exchange}.
 *
 * <p>Codes are 32 random bytes encoded as Base64URL (no padding), expire in
 * {@value #TTL_MINUTES} minutes, and may be consumed at most once. Reuse,
 * expiry, and unknown codes all surface the same generic error to avoid
 * leaking information.
 */
@Service
public class LoginCodeService {
    static final long TTL_MINUTES = 2;
    private static final int RANDOM_BYTES = 32;

    private final LoginCodeRepository loginCodes;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public LoginCodeService(LoginCodeRepository loginCodes) {
        this(loginCodes, new SecureRandom(), Clock.systemUTC());
    }

    // Package-private for tests; @Autowired above tells Spring to use the
    // public single-arg constructor at runtime so the presence of two
    // non-private constructors does not become an ambiguity.
    LoginCodeService(LoginCodeRepository loginCodes, SecureRandom secureRandom, Clock clock) {
        this.loginCodes = loginCodes;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(TTL_MINUTES));
        loginCodes.save(new LoginCode(code, user, expiresAt));
        return code;
    }

    @Transactional
    public User exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("invalid login code");
        }
        LoginCode loginCode = loginCodes.findByCodeAndConsumedAtIsNull(code)
                .orElseThrow(() -> new BadRequestException("invalid login code"));
        Instant now = clock.instant();
        if (loginCode.getExpiresAt().isBefore(now)) {
            throw new BadRequestException("invalid login code");
        }
        loginCode.consume(now);
        return loginCode.getUser();
    }
}
