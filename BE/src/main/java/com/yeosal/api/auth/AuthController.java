package com.yeosal.api.auth;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final LoginCodeService loginCodeService;
    private final String mobileRedirectUri;
    private final String kakaoClientId;
    private final String kakaoRedirectUri;

    public AuthController(
            AuthService authService,
            LoginCodeService loginCodeService,
            @Value("${yeosal.kakao.mobile-redirect-uri}") String mobileRedirectUri,
            @Value("${yeosal.kakao.client-id}") String kakaoClientId,
            @Value("${yeosal.kakao.redirect-uri}") String kakaoRedirectUri
    ) {
        this.authService = authService;
        this.loginCodeService = loginCodeService;
        this.mobileRedirectUri = mobileRedirectUri;
        this.kakaoClientId = kakaoClientId;
        this.kakaoRedirectUri = kakaoRedirectUri;
    }

    @PostMapping("/signup")
    public ApiResponse<AuthTokens> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.of(authService.signup(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokens> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request));
    }

    @GetMapping("/kakao/authorize")
    public RedirectView kakaoAuthorize() {
        String target = "https://kauth.kakao.com/oauth/authorize" +
                "?response_type=code" +
                "&client_id=" + encode(kakaoClientId) +
                "&redirect_uri=" + encode(kakaoRedirectUri);
        return new RedirectView(target);
    }

    @GetMapping("/kakao/callback")
    public RedirectView kakaoCallback(@RequestParam String code) {
        // Run the Kakao OAuth user lookup, then hand the mobile app a
        // single-use login code instead of access/refresh tokens. The app
        // exchanges the code for tokens via POST /auth/kakao/exchange so
        // tokens never appear in the deep-link query string.
        var user = authService.kakaoUserFor(code);
        String loginCode = loginCodeService.issue(user);
        String target = mobileRedirectUri + "?code=" + encode(loginCode);
        return new RedirectView(target);
    }

    @PostMapping("/kakao/exchange")
    public ApiResponse<AuthTokens> kakaoExchange(@Valid @RequestBody KakaoExchangeRequest request) {
        return ApiResponse.of(authService.exchangeKakaoLoginCode(request.code()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.of(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.of("ok");
    }

    public record SignupRequest(@Email String email, @NotBlank String password, @NotBlank String nickname) {}
    public record LoginRequest(@Email String email, @NotBlank String password) {}
    public record KakaoExchangeRequest(@NotBlank String code) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record AuthTokens(String accessToken, String refreshToken, String tokenType, UserDto user) {}
    public record UserDto(long id, String email, String nickname, String timezone) {
        static UserDto from(User user) {
            return new UserDto(user.getId(), user.getEmail(), user.getNickname(), user.getTimezone());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
