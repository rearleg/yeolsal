package com.yeosal.api.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
 * Story 5.2 — web slice for {@link RoomMemberCapController}. Six cases
 * cover the AC17 minimum-5 contract plus an extra missing-body branch.
 */
@WebMvcTest(
        value = RoomMemberCapController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        RoomMemberCapControllerTest.TestSecurityConfig.class
})
class RoomMemberCapControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final long ROOM_ID = 42L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-15T03:14:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private RoomMemberCapService capService;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("PATCH 200 happy — leader sets cap=20, response carries RoomSummary with pending fields")
    @WithMockUser
    void patchCap_leaderHappy_returnsRoomSummary() throws Exception {
        when(capService.updateMemberCap(eq(viewer), eq(ROOM_ID), eq(20)))
                .thenReturn(new RoomService.RoomSummary(
                        ROOM_ID, "test", VIEWER_USER_ID, 12, 20, CREATED_AT, 20, "2026-05"));

        mockMvc.perform(patch("/api/v1/rooms/{id}/members/cap", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) ROOM_ID))
                .andExpect(jsonPath("$.data.maxMembers").value(12))
                .andExpect(jsonPath("$.data.pendingMaxMembers").value(20))
                .andExpect(jsonPath("$.data.pendingMaxMembersEffectiveFromMonth").value("2026-05"));
    }

    @Test
    @DisplayName("PATCH 403 non-leader — service throws ForbiddenException")
    @WithMockUser
    void patchCap_nonLeader_returnsForbidden() throws Exception {
        when(capService.updateMemberCap(any(User.class), anyLong(), anyInt()))
                .thenThrow(new ForbiddenException("방장 권한이 필요합니다."));

        mockMvc.perform(patch("/api/v1/rooms/{id}/members/cap", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\":15}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("PATCH 400 missing maxMembers — @NotNull triggers VALIDATION, no service call")
    @WithMockUser
    void patchCap_missingField_returnsValidation() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/members/cap", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        verify(capService, never()).updateMemberCap(any(User.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("PATCH 400 below 2 — Bean Validation @Min fails")
    @WithMockUser
    void patchCap_below2_returnsValidation() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/members/cap", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        verify(capService, never()).updateMemberCap(any(User.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("PATCH 400 above 30 — Bean Validation @Max fails")
    @WithMockUser
    void patchCap_above30_returnsValidation() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/{id}/members/cap", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\":31}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("PATCH 404 unknown room — service throws NotFoundException")
    @WithMockUser
    void patchCap_unknownRoom_returnsNotFound() throws Exception {
        when(capService.updateMemberCap(any(User.class), anyLong(), anyInt()))
                .thenThrow(new NotFoundException("방을 찾을 수 없습니다."));

        mockMvc.perform(patch("/api/v1/rooms/{id}/members/cap", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMembers\":20}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------- helpers ----------

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
