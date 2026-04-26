package com.yeosal.api.auth;

import com.yeosal.api.common.BadRequestException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class KakaoAuthClient {
    private final RestClient restClient = RestClient.create();
    private final String clientId;
    private final String redirectUri;

    public KakaoAuthClient(
            @Value("${yeosal.kakao.client-id}") String clientId,
            @Value("${yeosal.kakao.redirect-uri}") String redirectUri
    ) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    public KakaoUser fetchUser(String authorizationCode) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            throw new BadRequestException("Kakao OAuth 설정이 없습니다.");
        }

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("redirect_uri", redirectUri);
        form.add("code", authorizationCode);

        KakaoTokenResponse token = restClient.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);

        if (token == null || token.access_token() == null) {
            throw new BadRequestException("Kakao token 발급에 실패했습니다.");
        }

        Map<?, ?> user = restClient.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(token.access_token()))
                .retrieve()
                .body(Map.class);

        if (user == null || !(user.get("kakao_account") instanceof Map<?, ?> account)) {
            throw new BadRequestException("Kakao 사용자 조회에 실패했습니다.");
        }
        Object email = account.get("email");
        Object profile = account.get("profile");
        String nickname = profile instanceof Map<?, ?> profileMap && profileMap.get("nickname") != null
                ? String.valueOf(profileMap.get("nickname"))
                : "카카오 사용자";
        if (email == null) {
            throw new BadRequestException("Kakao 이메일 제공 동의가 필요합니다.");
        }
        return new KakaoUser(String.valueOf(email), nickname);
    }

    public record KakaoUser(String email, String nickname) {}
    public record KakaoTokenResponse(String access_token) {}
}
