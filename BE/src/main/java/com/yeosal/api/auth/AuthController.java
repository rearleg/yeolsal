package com.yeosal.api.auth;

import com.yeosal.api.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    @PostMapping("/signup")
    public ApiResponse<AuthTokens> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.of(AuthTokens.devToken(request.email()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokens> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(AuthTokens.devToken(request.email()));
    }

    @PostMapping("/kakao")
    public ApiResponse<AuthTokens> kakao(@Valid @RequestBody KakaoLoginRequest request) {
        return ApiResponse.of(AuthTokens.devToken("kakao:" + request.authorizationCode()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.of(AuthTokens.devToken("refresh"));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout() {
        return ApiResponse.of("ok");
    }

    public record SignupRequest(@Email String email, @NotBlank String password, @NotBlank String nickname) {}
    public record LoginRequest(@Email String email, @NotBlank String password) {}
    public record KakaoLoginRequest(@NotBlank String authorizationCode) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record AuthTokens(String accessToken, String refreshToken, String tokenType) {
        static AuthTokens devToken(String subject) {
            return new AuthTokens("dev-access-" + subject, "dev-refresh-" + subject, "Bearer");
        }
    }
}
