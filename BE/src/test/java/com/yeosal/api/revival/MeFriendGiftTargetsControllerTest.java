package com.yeosal.api.revival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3.3 BE-8 / AC7 — web slice for
 * {@link MeFriendGiftTargetsController}. Mirrors
 * {@link FriendGiftReceiptsControllerTest} shape.
 */
@WebMvcTest(
        value = MeFriendGiftTargetsController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        MeFriendGiftTargetsControllerTest.TestSecurityConfig.class
})
class MeFriendGiftTargetsControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final Instant ELIMINATED_AT_1 = Instant.parse("2026-05-18T03:00:00Z");
    private static final Instant ELIMINATED_AT_2 = Instant.parse("2026-05-17T07:30:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private FriendGiftTargetQuery targetQuery;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("AC1 happy — single room with one eligible target → 200 + envelope")
    @WithMockUser
    void targets_happy_returns200WithEnvelope() throws Exception {
        when(targetQuery.findEligibleTargets(eq(VIEWER_USER_ID))).thenReturn(List.of(
                new FriendGiftTargetQuery.EligibleFriendRow(
                        42L, "Room A", 11L, "BBB", "RED", ELIMINATED_AT_1)));

        mockMvc.perform(get("/api/v1/me/friend-gift-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roomId").value(42))
                .andExpect(jsonPath("$.data[0].roomName").value("Room A"))
                .andExpect(jsonPath("$.data[0].eligibleCount").value(1))
                .andExpect(jsonPath("$.data[0].friends[0].userId").value(11))
                .andExpect(jsonPath("$.data[0].friends[0].nickname").value("BBB"))
                .andExpect(jsonPath("$.data[0].friends[0].status").value("RED"));
    }

    @Test
    @DisplayName("AC1 empty — no eligible rows → 200 + data:[] (never 404)")
    @WithMockUser
    void targets_emptyList_returns200WithEmptyArray() throws Exception {
        when(targetQuery.findEligibleTargets(eq(VIEWER_USER_ID))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/friend-gift-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("unauthenticated → 4xx (security chain gates the endpoint)")
    void targets_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/me/friend-gift-targets"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Caller not a member of any room → 200 + data:[] (no JOIN match)")
    @WithMockUser
    void targets_nonMember_returns200WithEmpty() throws Exception {
        // The query naturally returns empty when the caller has no
        // room_members rows; mirror that contract.
        when(targetQuery.findEligibleTargets(eq(VIEWER_USER_ID))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/friend-gift-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("AC1 large response — 50 entries, single room, no truncation")
    @WithMockUser
    void targets_largeResponse_noPagination() throws Exception {
        List<FriendGiftTargetQuery.EligibleFriendRow> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(new FriendGiftTargetQuery.EligibleFriendRow(
                    100L, "Crowd", 1000L + i, "Friend" + i,
                    (i % 2 == 0) ? "RED" : "SPECTATOR", ELIMINATED_AT_2));
        }
        when(targetQuery.findEligibleTargets(eq(VIEWER_USER_ID))).thenReturn(rows);

        mockMvc.perform(get("/api/v1/me/friend-gift-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roomId").value(100))
                .andExpect(jsonPath("$.data[0].eligibleCount").value(50))
                .andExpect(jsonPath("$.data[0].friends.length()").value(50));
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
