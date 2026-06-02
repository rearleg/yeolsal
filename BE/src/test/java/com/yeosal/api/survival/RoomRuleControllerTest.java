package com.yeosal.api.survival;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Story 5.1 — web slice for {@link RoomRuleController}. Seven cases cover
 * the AC13 minimum-6 contract for PATCH happy / PATCH 403 / PATCH 400 x2 /
 * PATCH 404 / GET happy / GET current-only.
 */
@WebMvcTest(
        value = RoomRuleController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        RoomRuleControllerTest.TestSecurityConfig.class
})
class RoomRuleControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final long ROOM_ID = 42L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-15T03:14:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private RoomRuleService roomRuleService;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("PATCH 200 happy — leader stages next-month rule, response carries DTO")
    @WithMockUser
    void patchRule_leaderHappy_returnsDto() throws Exception {
        when(roomRuleService.updateRule(eq(viewer), eq(ROOM_ID), eq("DAILY_UPDATE"), eq(false)))
                .thenReturn(new RoomRuleVersionDto(
                        1001L, "DAILY_UPDATE", false, "2026-05", VIEWER_USER_ID, CREATED_AT));

        mockMvc.perform(patch("/api/v1/rooms/{id}/rule", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.preset").value("DAILY_UPDATE"))
                .andExpect(jsonPath("$.data.weekendInclude").value(false))
                .andExpect(jsonPath("$.data.effectiveFromMonth").value("2026-05"))
                .andExpect(jsonPath("$.data.createdByUserId").value((int) VIEWER_USER_ID));
    }

    @Test
    @DisplayName("PATCH 403 non-leader — service throws ForbiddenException → handler maps to FORBIDDEN")
    @WithMockUser
    void patchRule_nonLeader_returnsForbidden() throws Exception {
        when(roomRuleService.updateRule(any(User.class), eq(ROOM_ID), anyString(), anyBoolean()))
                .thenThrow(new ForbiddenException("방장 권한이 필요합니다."));

        mockMvc.perform(patch("/api/v1/rooms/{id}/rule", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("PATCH 400 invalid preset — @Pattern fails at Bean Validation, no service call")
    @WithMockUser
    void patchRule_invalidPreset_returnsValidation() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/rule", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"WEEKLY\",\"weekendInclude\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        verify(roomRuleService, never()).updateRule(any(User.class), anyLong(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("PATCH 400 missing weekendInclude — @NotNull on boxed Boolean catches missing field")
    @WithMockUser
    void patchRule_missingWeekendInclude_returnsValidation() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/rule", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"DAILY_UPDATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        verify(roomRuleService, never()).updateRule(any(User.class), anyLong(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("PATCH 404 unknown room — service throws NotFoundException → handler maps to NOT_FOUND")
    @WithMockUser
    void patchRule_unknownRoom_returnsNotFound() throws Exception {
        when(roomRuleService.updateRule(any(User.class), eq(ROOM_ID), anyString(), anyBoolean()))
                .thenThrow(new NotFoundException("방을 찾을 수 없습니다."));

        mockMvc.perform(patch("/api/v1/rooms/{id}/rule", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET 200 happy — current + pending both present in response")
    @WithMockUser
    void getRule_memberWithPending_returnsBoth() throws Exception {
        when(roomRuleService.getRule(eq(viewer), eq(ROOM_ID)))
                .thenReturn(new RoomRuleStateDto(
                        new RoomRuleVersionDto(
                                500L, "DAILY_UPDATE", true, "2026-04", VIEWER_USER_ID, CREATED_AT),
                        new RoomRuleVersionDto(
                                700L, "DAILY_UPDATE", false, "2026-05", VIEWER_USER_ID, CREATED_AT)));

        mockMvc.perform(get("/api/v1/rooms/{id}/rule", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.id").value(500))
                .andExpect(jsonPath("$.data.current.weekendInclude").value(true))
                .andExpect(jsonPath("$.data.current.effectiveFromMonth").value("2026-04"))
                .andExpect(jsonPath("$.data.pending.id").value(700))
                .andExpect(jsonPath("$.data.pending.weekendInclude").value(false))
                .andExpect(jsonPath("$.data.pending.effectiveFromMonth").value("2026-05"));
    }

    @Test
    @DisplayName("GET 200 current-only — pending is null when no pending row exists")
    @WithMockUser
    void getRule_memberCurrentOnly_returnsNullPending() throws Exception {
        when(roomRuleService.getRule(eq(viewer), eq(ROOM_ID)))
                .thenReturn(new RoomRuleStateDto(
                        new RoomRuleVersionDto(
                                500L, "DAILY_UPDATE", true, "2026-04", VIEWER_USER_ID, CREATED_AT),
                        null));

        mockMvc.perform(get("/api/v1/rooms/{id}/rule", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.id").value(500))
                .andExpect(jsonPath("$.data.pending").doesNotExist());
    }

    // ----- helpers -----

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
