package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.JwtAuthenticationFilter;
import com.yeosal.api.common.ApiExceptionHandler;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.RateLimitFilter;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
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
 * Web slice for {@link MeSurvivalController} (Epic 1 retro T4).
 *
 * <p>Verifies the envelope shape, parameter forwarding, and the
 * unauthenticated 4xx gate for {@code GET /api/v1/me/survival}. Mirrors
 * the {@link SurvivalStateControllerTest} structure for review parity.
 */
@WebMvcTest(
        value = MeSurvivalController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        MeSurvivalControllerTest.TestSecurityConfig.class
})
class MeSurvivalControllerTest {

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
    @DisplayName("GET /api/v1/me/survival → 200 + ApiResponse envelope with roomId/roomName/status per entry")
    @WithMockUser
    void mine_returns200WithEnvelope() throws Exception {
        when(survivalStateService.mySurvivalAcrossRooms(VIEWER_USER_ID)).thenReturn(List.of(
                new MeSurvivalEntryDto(11L, "팀 A", SurvivalStatus.ACTIVE),
                new MeSurvivalEntryDto(12L, "팀 B", SurvivalStatus.SPECTATOR)));

        mockMvc.perform(get("/api/v1/me/survival"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].roomId").value(11))
                .andExpect(jsonPath("$.data[0].roomName").value("팀 A"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].roomId").value(12))
                .andExpect(jsonPath("$.data[1].status").value("SPECTATOR"));
    }

    @Test
    @DisplayName("controller forwards the authenticated user id to the service")
    @WithMockUser
    void mine_forwardsViewerIdToService() throws Exception {
        when(survivalStateService.mySurvivalAcrossRooms(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/survival")).andExpect(status().isOk());

        ArgumentCaptor<Long> viewerIdCap = ArgumentCaptor.forClass(Long.class);
        verify(survivalStateService).mySurvivalAcrossRooms(viewerIdCap.capture());
        assertThat(viewerIdCap.getValue()).isEqualTo(VIEWER_USER_ID);
    }

    @Test
    @DisplayName("empty memberships → 200 with empty data array (not 404)")
    @WithMockUser
    void mine_emptyMemberships_returnsEmptyArray() throws Exception {
        when(survivalStateService.mySurvivalAcrossRooms(VIEWER_USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/survival"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void mine_unauthenticated_returns_4xx() throws Exception {
        mockMvc.perform(get("/api/v1/me/survival"))
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
