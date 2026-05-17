package com.yeosal.api.revival;

import static org.mockito.ArgumentMatchers.any;
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
 * Web slice for {@link RevivalController} (Story 3.1 AC11 BE-8.2).
 *
 * <p>Loads only the controller + {@link ApiExceptionHandler} via
 * {@code @WebMvcTest}, mocks the service + {@link CurrentUser} +
 * {@link RoomMemberRepository}, and verifies the envelope shape,
 * validation handling, and the security/membership gates.
 */
@WebMvcTest(
        value = RevivalController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        RevivalControllerTest.TestSecurityConfig.class
})
class RevivalControllerTest {

    private static final long ROOM_ID = 42L;
    private static final long VIEWER_USER_ID = 7L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-16T03:14:15Z");

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
    @DisplayName("POST /api/v1/rooms/{id}/revival {source:FREE_TICKET} → 200 + envelope")
    @WithMockUser
    void revive_freeTicket_returns200WithEnvelope() throws Exception {
        when(revivalService.reviveSelf(ROOM_ID, VIEWER_USER_ID, RevivalSource.FREE_TICKET))
                .thenReturn(new RevivalEventDto(9001L, "FREE_TICKET", 0, 5, OCCURRED_AT));

        mockMvc.perform(post("/api/v1/rooms/{id}/revival", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FREE_TICKET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revivalEventId").value(9001))
                .andExpect(jsonPath("$.data.source").value("FREE_TICKET"))
                .andExpect(jsonPath("$.data.pointsSpent").value(0))
                .andExpect(jsonPath("$.data.roomPointPoolAfter").value(5));
    }

    @Test
    @DisplayName("invalid source value → 400 VALIDATION (HttpMessageNotReadableException handler)")
    @WithMockUser
    void revive_invalidSource_returns400Validation() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revival", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("missing source field → 400 VALIDATION")
    @WithMockUser
    void revive_missingSource_returns400Validation() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revival", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("non-member of room → 403 FORBIDDEN")
    @WithMockUser
    void revive_nonMember_returns403() throws Exception {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, VIEWER_USER_ID)).thenReturn(false);

        mockMvc.perform(post("/api/v1/rooms/{id}/revival", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FREE_TICKET\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void revive_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/revival", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"FREE_TICKET\"}"))
                .andExpect(status().is4xxClientError());
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
