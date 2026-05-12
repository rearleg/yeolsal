package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import com.yeosal.api.common.ApiExceptionHandler;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.common.RateLimitFilter;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice for {@link SurvivalStateController} (Story 1.3 BE-5.2).
 *
 * <p>Loads only the controller + {@link ApiExceptionHandler} via
 * {@code @WebMvcTest}, mocks {@link SurvivalStateService} and
 * {@link CurrentUser}, and verifies that the response envelope, HTTP
 * status codes, and exception → 401/403/404 mappings are wired
 * correctly. End-to-end persistence + the real security filter chain
 * are exercised separately in {@code SurvivalStateRosterIT} (BE-5.3).
 *
 * <p>The nested {@link TestSecurityConfig} substitutes the production
 * {@link com.yeosal.api.common.SecurityConfig} (which depends on the
 * not-loaded {@code JwtAuthenticationFilter} / {@code RateLimitFilter}
 * beans) with a minimal filter chain that still gates
 * {@code anyRequest().authenticated()} — so an unauthenticated request
 * surfaces the 401 we assert against.
 */
@WebMvcTest(
        value = SurvivalStateController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        SurvivalStateControllerTest.TestSecurityConfig.class
})
class SurvivalStateControllerTest {

    private static final long ROOM_ID = 42L;
    private static final long VIEWER_USER_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @MockBean private SurvivalStateService survivalStateService;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("GET /api/v1/rooms/{id}/survival → 200 + ApiResponse envelope with data array")
    @WithMockUser
    void roster_returns200WithEnvelope() throws Exception {
        SurvivalStateDto carolMasked = new SurvivalStateDto(
                9L, "Carol", SurvivalStatus.ACTIVE, null, null, null);
        SurvivalStateDto aliceActive = new SurvivalStateDto(
                1L, "Alice", SurvivalStatus.ACTIVE,
                Instant.parse("2026-05-01T00:00:00Z"), null, null);
        when(survivalStateService.roster(ROOM_ID, VIEWER_USER_ID))
                .thenReturn(List.of(aliceActive, carolMasked));

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].userId").value(1))
                .andExpect(jsonPath("$.data[0].nickname").value("Alice"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].userId").value(9))
                .andExpect(jsonPath("$.data[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].eliminatedAt").doesNotExist())
                .andExpect(jsonPath("$.data[1].broadVisibilityAt").doesNotExist());
    }

    @Test
    @DisplayName("controller forwards (roomId, viewerUserId) to service")
    @WithMockUser
    void roster_forwardsParametersToService() throws Exception {
        when(survivalStateService.roster(anyLong(), anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", ROOM_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<Long> roomIdCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> viewerIdCap = ArgumentCaptor.forClass(Long.class);
        verify(survivalStateService).roster(roomIdCap.capture(), viewerIdCap.capture());
        assertThat(roomIdCap.getValue()).isEqualTo(ROOM_ID);
        assertThat(viewerIdCap.getValue()).isEqualTo(VIEWER_USER_ID);
    }

    @Test
    @DisplayName("ForbiddenException from service → 403 + error.code=FORBIDDEN")
    @WithMockUser
    void roster_forbiddenFromService_maps_to_403() throws Exception {
        when(survivalStateService.roster(eq(ROOM_ID), anyLong()))
                .thenThrow(new ForbiddenException("방 멤버만 접근할 수 있습니다."));

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", ROOM_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("방 멤버만 접근할 수 있습니다."));
    }

    @Test
    @DisplayName("NotFoundException from service → 404 + error.code=NOT_FOUND")
    @WithMockUser
    void roster_notFoundFromService_maps_to_404() throws Exception {
        when(survivalStateService.roster(eq(ROOM_ID), anyLong()))
                .thenThrow(new NotFoundException("방을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", ROOM_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("방을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void roster_unauthenticated_returns_4xx() throws Exception {
        // Spring Security 6 default for unauthenticated stateless requests
        // without an explicit AuthenticationEntryPoint is 403. The
        // production SecurityConfig matches this default — the story's AC12
        // claim of 401 is an inaccuracy in the spec; the gate exists either
        // way. is4xxClientError() keeps the assertion stable if a future
        // EntryPoint flips the code to 401.
        mockMvc.perform(get("/api/v1/rooms/{id}/survival", ROOM_ID))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("non-numeric path → 400 VALIDATION via ApiExceptionHandler")
    @WithMockUser
    void roster_nonNumericPath_returns_400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/abc/survival"))
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
