package com.yeosal.api.room.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import com.yeosal.api.common.ApiExceptionHandler;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.RateLimitFilter;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Instant;
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
 * Web slice for {@link KudosController} (Story 3.5 AC11 BE-11.2).
 *
 * <p>Loads only the controller + {@link ApiExceptionHandler} via
 * {@code @WebMvcTest}, mocks the service + {@link CurrentUser}, and
 * verifies the 201/envelope shape, validation handling, and each
 * service-thrown wire code.
 */
@WebMvcTest(
        value = KudosController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        KudosControllerTest.TestSecurityConfig.class
})
class KudosControllerTest {

    private static final long ROOM_ID = 42L;
    private static final long VIEWER_USER_ID = 7L;
    private static final long TARGET_USER_ID = 11L;
    private static final long KUDOS_ID = 9001L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-17T03:14:15Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private KudosService kudosService;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("POST /api/v1/rooms/{id}/kudos {targetUserId:11} → 201 + envelope")
    @WithMockUser
    void send_happyNullMessage_returns201() throws Exception {
        when(kudosService.sendKudos(eq(ROOM_ID), eq(viewer), eq(TARGET_USER_ID), eq(null)))
                .thenReturn(new KudosDto(
                        KUDOS_ID, ROOM_ID, VIEWER_USER_ID, TARGET_USER_ID, "", OCCURRED_AT));

        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kudosId").value(KUDOS_ID))
                .andExpect(jsonPath("$.data.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.data.senderUserId").value(VIEWER_USER_ID))
                .andExpect(jsonPath("$.data.targetUserId").value(TARGET_USER_ID))
                .andExpect(jsonPath("$.data.message").value(""));
    }

    @Test
    @DisplayName("empty-string message → 201 + envelope.message ''")
    @WithMockUser
    void send_emptyMessage_returns201() throws Exception {
        when(kudosService.sendKudos(eq(ROOM_ID), eq(viewer), eq(TARGET_USER_ID), eq("")))
                .thenReturn(new KudosDto(
                        KUDOS_ID, ROOM_ID, VIEWER_USER_ID, TARGET_USER_ID, "", OCCURRED_AT));

        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + ",\"message\":\"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message").value(""));
    }

    @Test
    @DisplayName("message > 60 chars → 400 VALIDATION")
    @WithMockUser
    void send_messageTooLong_returns400Validation() throws Exception {
        String tooLong = "0".repeat(61);
        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID
                                + ",\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("missing targetUserId → 400 VALIDATION")
    @WithMockUser
    void send_missingTarget_returns400Validation() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("non-numeric targetUserId → 400 VALIDATION (HttpMessageNotReadable)")
    @WithMockUser
    void send_nonNumericTarget_returns400Validation() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void send_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("service throws KudosAlreadySentTodayException → 409 + KUDOS_ALREADY_SENT_TODAY")
    @WithMockUser
    void send_alreadySentToday_returns409() throws Exception {
        when(kudosService.sendKudos(anyLong(), any(User.class), anyLong(), any()))
                .thenThrow(new KudosAlreadySentTodayException("오늘은 이미 응원을 보냈어요."));

        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KUDOS_ALREADY_SENT_TODAY"));
    }

    @Test
    @DisplayName("service throws KudosTargetNotEligibleException → 400 + KUDOS_TARGET_NOT_ELIGIBLE")
    @WithMockUser
    void send_targetNotEligible_returns400() throws Exception {
        when(kudosService.sendKudos(anyLong(), any(User.class), anyLong(), any()))
                .thenThrow(new KudosTargetNotEligibleException(
                        "응원은 회생을 기다리는 멤버에게만 보낼 수 있어요."));

        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("KUDOS_TARGET_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("service throws NotFriendsException → 403 + NOT_FRIENDS")
    @WithMockUser
    void send_notFriends_returns403() throws Exception {
        when(kudosService.sendKudos(anyLong(), any(User.class), anyLong(), any()))
                .thenThrow(new NotFriendsException("친구가 된 멤버에게만 응원을 보낼 수 있어요."));

        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_FRIENDS"));
    }

    @Test
    @DisplayName("service throws SpectatorWriteForbiddenException → 403 + SPECTATOR_WRITE_FORBIDDEN")
    @WithMockUser
    void send_spectator_returns403() throws Exception {
        when(kudosService.sendKudos(anyLong(), any(User.class), anyLong(), any()))
                .thenThrow(new SpectatorWriteForbiddenException());

        mockMvc.perform(post("/api/v1/rooms/{id}/kudos", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SPECTATOR_WRITE_FORBIDDEN"));
    }

    // ----- helpers -----

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
