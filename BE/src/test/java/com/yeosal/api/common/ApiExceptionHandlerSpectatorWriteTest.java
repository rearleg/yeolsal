package com.yeosal.api.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import com.yeosal.api.room.chat.ChatController;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 2.1 AC4 / AC10 — verifies the wire shape of
 * {@code SpectatorWriteForbiddenException} when {@code ChatService} throws it
 * inside the {@code POST /api/v1/rooms/{id}/messages} flow.
 *
 * <p>Also pins the regression: the new {@code @ExceptionHandler} for the
 * subtype MUST NOT shadow the parent handler — a plain
 * {@code ForbiddenException} thrown by the service still maps to the
 * generic {@code "FORBIDDEN"} code.
 */
@WebMvcTest(
        value = ChatController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        ApiExceptionHandlerSpectatorWriteTest.TestSecurityConfig.class
})
class ApiExceptionHandlerSpectatorWriteTest {

    private static final long VIEWER_USER_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @MockBean private ChatService chatService;
    @MockBean private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        User viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("POST messages → 403 + SPECTATOR_WRITE_FORBIDDEN when service throws the subtype")
    @WithMockUser
    void spectatorWrite_maps_to_403_with_subtype_code() throws Exception {
        when(chatService.sendUserMessage(any(User.class), anyLong(), anyString()))
                .thenThrow(new SpectatorWriteForbiddenException());

        mockMvc.perform(post("/api/v1/rooms/1/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"안녕\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(SpectatorWriteForbiddenException.CODE))
                .andExpect(jsonPath("$.error.message").value("관전 중에는 메시지를 보낼 수 없어요."));
    }

    @Test
    @DisplayName("regression: generic ForbiddenException still maps to code FORBIDDEN (parent handler not shadowed)")
    @WithMockUser
    void genericForbidden_still_maps_to_FORBIDDEN_code() throws Exception {
        when(chatService.sendUserMessage(any(User.class), anyLong(), anyString()))
                .thenThrow(new ForbiddenException("방 멤버만 접근할 수 있습니다."));

        mockMvc.perform(post("/api/v1/rooms/1/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"안녕\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("방 멤버만 접근할 수 있습니다."));
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return u;
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }
}
