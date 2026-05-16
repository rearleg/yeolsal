package com.yeosal.api.survival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import com.yeosal.api.common.ApiExceptionHandler;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.RateLimitFilter;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
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
 * Web slice for {@link RecordVisibilityController} (Story 2.3 AC2 + AC10).
 *
 * <p>Verifies the envelope shape on GET, the upsert round-trip on POST,
 * the @NotNull → 400 VALIDATION path, and the membership-guard 403 mapped
 * by {@link ApiExceptionHandler}. Mirrors {@code MeSurvivalControllerTest}
 * structure for review parity.
 */
@WebMvcTest(
        value = RecordVisibilityController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        RecordVisibilityControllerTest.TestSecurityConfig.class
})
class RecordVisibilityControllerTest {

    private static final long VIEWER_USER_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @MockBean private RecordVisibilityService service;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("GET /me/visibility-prefs → 200 + envelope with one entry per room")
    @WithMockUser
    void list_returns200WithEnvelope() throws Exception {
        when(service.listForUser(eq(viewer))).thenReturn(List.of(
                new VisibilityPrefDto(10L, "팀 A", true, Instant.parse("2026-05-16T01:00:00Z")),
                new VisibilityPrefDto(11L, "팀 B", false, null)));

        mockMvc.perform(get("/api/v1/me/visibility-prefs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].roomId").value(10))
                .andExpect(jsonPath("$.data[0].roomName").value("팀 A"))
                .andExpect(jsonPath("$.data[0].shareOnElimination").value(true))
                .andExpect(jsonPath("$.data[1].roomId").value(11))
                .andExpect(jsonPath("$.data[1].shareOnElimination").value(false))
                .andExpect(jsonPath("$.data[1].updatedAt").isEmpty());
    }

    @Test
    @DisplayName("POST /me/visibility-prefs with valid body → 200 + new value reflected")
    @WithMockUser
    void upsert_validBody_returns200() throws Exception {
        when(service.upsert(eq(viewer), eq(10L), eq(true))).thenReturn(
                new VisibilityPrefDto(10L, "팀 A", true, Instant.parse("2026-05-16T02:00:00Z")));

        mockMvc.perform(post("/api/v1/me/visibility-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":10,\"shareOnElimination\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(10))
                .andExpect(jsonPath("$.data.shareOnElimination").value(true));

        verify(service).upsert(viewer, 10L, true);
    }

    @Test
    @DisplayName("POST with missing roomId → 400 VALIDATION")
    @WithMockUser
    void upsert_missingRoomId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/me/visibility-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shareOnElimination\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("POST with missing shareOnElimination → 400 VALIDATION")
    @WithMockUser
    void upsert_missingShare_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/me/visibility-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("POST for non-member room → 403 FORBIDDEN")
    @WithMockUser
    void upsert_nonMember_returns403() throws Exception {
        when(service.upsert(eq(viewer), anyLong(), anyBoolean()))
                .thenThrow(new ForbiddenException("해당 그룹의 멤버가 아닙니다."));

        mockMvc.perform(post("/api/v1/me/visibility-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":99,\"shareOnElimination\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void list_unauthenticated_returns_4xx() throws Exception {
        mockMvc.perform(get("/api/v1/me/visibility-prefs"))
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
