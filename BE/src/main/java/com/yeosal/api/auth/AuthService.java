package com.yeosal.api.auth;

import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final KakaoAuthClient kakaoAuthClient;
    private final LoginCodeService loginCodeService;
    private final long refreshTokenDays;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            KakaoAuthClient kakaoAuthClient,
            LoginCodeService loginCodeService,
            @Value("${yeosal.auth.refresh-token-days}") long refreshTokenDays
    ) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.kakaoAuthClient = kakaoAuthClient;
        this.loginCodeService = loginCodeService;
        this.refreshTokenDays = refreshTokenDays;
    }

    @Transactional
    public AuthController.AuthTokens signup(AuthController.SignupRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new BadRequestException("이미 가입된 이메일입니다.");
        }
        User user = users.save(new User(
                request.email().toLowerCase(),
                request.nickname(),
                passwordEncoder.encode(request.password()),
                AuthProvider.EMAIL
        ));
        return issue(user);
    }

    @Transactional
    public AuthController.AuthTokens login(AuthController.LoginRequest request) {
        User user = users.findByEmail(request.email().toLowerCase())
                .orElseThrow(() -> new BadRequestException("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return issue(user);
    }

    @Transactional
    public AuthController.AuthTokens kakao(AuthController.KakaoLoginRequest request) {
        return issue(kakaoUserFor(request.authorizationCode()));
    }

    /**
     * Run the Kakao OAuth user lookup/create flow without issuing yeosal
     * tokens. Used by the deep-link callback path so the caller can hand
     * off a single-use login code instead of placing tokens in a redirect
     * URL query string.
     */
    @Transactional
    public User kakaoUserFor(String authorizationCode) {
        KakaoAuthClient.KakaoUser kakaoUser = kakaoAuthClient.fetchUser(authorizationCode);
        User user = users.findByEmail(kakaoUser.email().toLowerCase())
                .orElseGet(() -> users.save(new User(
                        kakaoUser.email().toLowerCase(),
                        kakaoUser.nickname(),
                        null,
                        AuthProvider.KAKAO
                )));
        user.setAuthProvider(AuthProvider.KAKAO);
        return user;
    }

    /**
     * Consume a single-use Kakao login code (issued by /auth/kakao/callback)
     * and return a fresh access/refresh token pair for the bound user.
     */
    @Transactional
    public AuthController.AuthTokens exchangeKakaoLoginCode(String code) {
        return issue(loginCodeService.exchange(code));
    }

    @Transactional
    public AuthController.AuthTokens refresh(String refreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(hash(refreshToken))
                .orElseThrow(() -> new BadRequestException("유효하지 않은 refresh token입니다."));
        if (stored.getRevokedAt() != null || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("만료된 refresh token입니다.");
        }
        stored.revoke();
        return issue(stored.getUser());
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.findByTokenHash(hash(refreshToken)).ifPresent(RefreshToken::revoke);
    }

    private AuthController.AuthTokens issue(User user) {
        String refreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        refreshTokens.save(new RefreshToken(
                user,
                hash(refreshToken),
                Instant.now().plusSeconds(refreshTokenDays * 24 * 60 * 60)
        ));
        return new AuthController.AuthTokens(jwtService.createAccessToken(user), refreshToken, "Bearer", AuthController.UserDto.from(user));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
