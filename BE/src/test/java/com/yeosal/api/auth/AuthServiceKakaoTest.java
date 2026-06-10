package com.yeosal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceKakaoTest {
    private UserRepository users;
    private KakaoAuthClient kakaoAuthClient;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        kakaoAuthClient = mock(KakaoAuthClient.class);
        service = new AuthService(
                users,
                mock(RefreshTokenRepository.class),
                mock(PasswordEncoder.class),
                mock(JwtService.class),
                kakaoAuthClient,
                mock(LoginCodeService.class),
                30
        );
    }

    @Test
    void kakaoUserFor_newEmail_marksAccountAsNew() {
        when(kakaoAuthClient.fetchUser("code"))
                .thenReturn(new KakaoAuthClient.KakaoUser("new@example.com", "new"));
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(users.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthService.KakaoLoginUser result = service.kakaoUserFor("code");

        assertThat(result.newAccount()).isTrue();
        assertThat(result.user().getAuthProvider()).isEqualTo(AuthProvider.KAKAO);
        verify(users).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void kakaoUserFor_existingEmail_marksAccountAsReturning() {
        User existing = new User("old@example.com", "old", "hash", AuthProvider.EMAIL);
        when(kakaoAuthClient.fetchUser("code"))
                .thenReturn(new KakaoAuthClient.KakaoUser("old@example.com", "old"));
        when(users.findByEmail("old@example.com")).thenReturn(Optional.of(existing));

        AuthService.KakaoLoginUser result = service.kakaoUserFor("code");

        assertThat(result.newAccount()).isFalse();
        assertThat(result.user()).isSameAs(existing);
        assertThat(existing.getAuthProvider()).isEqualTo(AuthProvider.KAKAO);
    }
}
