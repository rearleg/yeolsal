package com.yeosal.api.revival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import com.yeosal.api.common.ApiExceptionHandler;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.RateLimitFilter;
import com.yeosal.api.room.RoomMemberRepository;
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
 * Story 3.2 AC12 — web slice for {@link RevivalController#reviveFriend}.
 * Mirrors {@link RevivalControllerTest} shape.
 */
@WebMvcTest(
        value = RevivalController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        FriendGiftControllerTest.TestSecurityConfig.class
})
class FriendGiftControllerTest {

    private static final long ROOM_ID = 42L;
    private static final long VIEWER_USER_ID = 7L;
    private static final long TARGET_USER_ID = 11L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-18T03:14:15Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private RevivalService revivalService;
    @MockBean private CurrentUser currentUser;
    @MockBean private RoomMemberRepository roomMembers;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, VIEWER_USER_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("POST /api/v1/rooms/{id}/revivals/gifts → 200 + envelope")
    @WithMockUser
    void reviveFriend_happy_returns200WithEnvelope() throws Exception {
        when(revivalService.reviveFriend(
                        eq(ROOM_ID), eq(viewer), eq(TARGET_USER_ID), any()))
                .thenReturn(new FriendGiftRevivalDto(
                        9002L, "FRIEND_GIFT", 5, 5, OCCURRED_AT, true, "Receiver"));

        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revivalEventId").value(9002))
                .andExpect(jsonPath("$.data.source").value("FRIEND_GIFT"))
                .andExpect(jsonPath("$.data.pointsSpent").value(5))
                .andExpect(jsonPath("$.data.roomPointPoolAfter").value(5))
                .andExpect(jsonPath("$.data.isFirstEverFriendGiftSend").value(true))
                .andExpect(jsonPath("$.data.receiverNickname").value("Receiver"));
    }

    @Test
    @DisplayName("path id non-numeric → 400 VALIDATION via MethodArgumentTypeMismatch")
    @WithMockUser
    void reviveFriend_nonNumericPath_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("body missing targetUserId → 400 VALIDATION via @Valid")
    @WithMockUser
    void reviveFriend_missingTargetUserId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("body targetUserId non-numeric → 400 VALIDATION via HttpMessageNotReadable")
    @WithMockUser
    void reviveFriend_nonNumericTargetUserId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("non-member room → 403 FORBIDDEN")
    @WithMockUser
    void reviveFriend_nonMember_returns403() throws Exception {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, VIEWER_USER_ID)).thenReturn(false);

        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void reviveFriend_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID + "}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Story 3.3 AC3 — body sourceSubtype=WALLET_INITIATED → service receives the enum verbatim")
    @WithMockUser
    void reviveFriend_walletInitiatedBody_servicePassthrough() throws Exception {
        when(revivalService.reviveFriend(
                        eq(ROOM_ID), eq(viewer), eq(TARGET_USER_ID),
                        eq(RevivalSourceSubtype.WALLET_INITIATED)))
                .thenReturn(new FriendGiftRevivalDto(
                        9003L, "FRIEND_GIFT", 5, 10, OCCURRED_AT, false, "Receiver"));

        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID
                                + ",\"sourceSubtype\":\"WALLET_INITIATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revivalEventId").value(9003))
                .andExpect(jsonPath("$.data.roomPointPoolAfter").value(10));
    }

    @Test
    @DisplayName("Story 3.3 AC3 — body sourceSubtype=INVALID_VALUE → 400 VALIDATION via HttpMessageNotReadable")
    @WithMockUser
    void reviveFriend_invalidSubtype_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revivals/gifts", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_USER_ID
                                + ",\"sourceSubtype\":\"INVALID_VALUE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
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
