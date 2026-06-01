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
 * Story 3.4 AC8 / BE-7 — web slice for
 * {@link MePersonalPointsLedgerController}. Mirrors
 * {@link FriendGiftReceiptsControllerTest} shape.
 *
 * <p>Four cases enumerated at AC8:
 * <ol>
 *   <li>Happy path → 200 OK envelope with mixed reasons.</li>
 *   <li>Missing {@code ?roomId=} param → 400 VALIDATION
 *       (mapped via {@link ApiExceptionHandler#requestParamValidation}).</li>
 *   <li>Auth absent → 4xx.</li>
 *   <li>Caller not a member of roomId → 200 + empty list (no SQL match).</li>
 * </ol>
 */
@WebMvcTest(
        value = MePersonalPointsLedgerController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        MePersonalPointsLedgerControllerTest.TestSecurityConfig.class
})
class MePersonalPointsLedgerControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final long ROOM_ID = 42L;
    private static final Instant T0 = Instant.parse("2026-05-22T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-22T01:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private PersonalPointsLedgerRepository ledger;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("AC2 happy — DESC ordered list with mixed reasons → 200 + envelope")
    @WithMockUser
    void ledger_happy_returns200() throws Exception {
        PersonalPointsLedger spent = makeLedger(
                501L, VIEWER_USER_ID, ROOM_ID, (short) -3,
                LedgerReason.REVIVAL_SPEND, T1, 99L);
        PersonalPointsLedger earned = makeLedger(
                500L, VIEWER_USER_ID, ROOM_ID, (short) 1,
                LedgerReason.SURVIVAL, T0, null);
        when(ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(eq(VIEWER_USER_ID), eq(ROOM_ID)))
                .thenReturn(List.of(spent, earned));

        mockMvc.perform(get("/api/v1/me/personal-points-ledger").param("roomId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(501))
                .andExpect(jsonPath("$.data[0].delta").value(-3))
                .andExpect(jsonPath("$.data[0].reason").value("REVIVAL_SPEND"))
                .andExpect(jsonPath("$.data[0].revivalEventId").value(99))
                .andExpect(jsonPath("$.data[1].id").value(500))
                .andExpect(jsonPath("$.data[1].delta").value(1))
                .andExpect(jsonPath("$.data[1].reason").value("SURVIVAL"))
                .andExpect(jsonPath("$.data[1].revivalEventId").isEmpty());
    }

    @Test
    @DisplayName("AC2 missing roomId param → 400 VALIDATION")
    @WithMockUser
    void ledger_missingRoomId_returns400Validation() throws Exception {
        mockMvc.perform(get("/api/v1/me/personal-points-ledger"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("auth absent → 4xx (TestSecurityConfig anyRequest().authenticated())")
    void ledger_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/me/personal-points-ledger").param("roomId", "42"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("AC2 — non-member roomId → 200 + empty list (no SQL match)")
    @WithMockUser
    void ledger_nonMember_returns200Empty() throws Exception {
        when(ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(eq(VIEWER_USER_ID), eq(ROOM_ID)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/personal-points-ledger").param("roomId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private static PersonalPointsLedger makeLedger(
            long id, long userId, long roomId, short delta,
            LedgerReason reason, Instant occurredAt, Long revivalEventId) {
        PersonalPointsLedger l = new PersonalPointsLedger(
                userId, roomId, delta, reason, occurredAt, revivalEventId);
        setField(l, "id", id);
        return l;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
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
