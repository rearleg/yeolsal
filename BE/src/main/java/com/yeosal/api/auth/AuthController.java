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
    private final String mobileRedirectUri;

    public AuthController(AuthService authService, @Value("${yeosal.kakao.mobile-redirect-uri}") String mobileRedirectUri) {
        this.authService = authService;
        this.mobileRedirectUri = mobileRedirectUri;
    }

    @PostMapping("/signup")
    public ApiResponse<AuthTokens> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.of(authService.signup(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokens> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request));
    }

    @PostMapping("/kakao")
    public ApiResponse<AuthTokens> kakao(@Valid @RequestBody KakaoLoginRequest request) {
        return ApiResponse.of(authService.kakao(request));
    }

    @GetMapping("/kakao/callback")
    public RedirectView kakaoCallback(@RequestParam String code) {
        AuthTokens tokens = authService.kakao(new KakaoLoginRequest(code));
        String target = mobileRedirectUri +
                "?accessToken=" + encode(tokens.accessToken()) +
                "&refreshToken=" + encode(tokens.refreshToken()) +
                "&tokenType=" + encode(tokens.tokenType()) +
                "&userId=" + tokens.user().id() +
                "&email=" + encode(tokens.user().email()) +
                "&nickname=" + encode(tokens.user().nickname()) +
                "&timezone=" + encode(tokens.user().timezone());
        return new RedirectView(target);
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
    public record KakaoLoginRequest(@NotBlank String authorizationCode) {}
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
