package com.yeosal.api.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
 * Story 5.2 — web slice for {@link TransferLeadershipController}. Five
 * cases cover the AC17 minimum-4 contract plus an extra zero-id branch.
 */
@WebMvcTest(
        value = TransferLeadershipController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        TransferLeadershipControllerTest.TestSecurityConfig.class
})
class TransferLeadershipControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final long ROOM_ID = 42L;
    private static final long TARGET_ID = 11L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-15T03:14:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private TransferLeadershipService transferService;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("POST 200 happy — leader transfers, response carries new ownerId")
    @WithMockUser
    void postTransfer_leaderHappy_returnsRoomSummary() throws Exception {
        when(transferService.transferLeadership(eq(viewer), eq(ROOM_ID), eq(TARGET_ID)))
                .thenReturn(new RoomService.RoomSummary(
                        ROOM_ID, "test", TARGET_ID, 12, 20, CREATED_AT, null, null));

        mockMvc.perform(post("/api/v1/rooms/{id}/transfer-leadership", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) ROOM_ID))
                .andExpect(jsonPath("$.data.ownerId").value((int) TARGET_ID));
    }

    @Test
    @DisplayName("POST 403 non-leader — service throws ForbiddenException")
    @WithMockUser
    void postTransfer_nonLeader_returnsForbidden() throws Exception {
        when(transferService.transferLeadership(any(User.class), anyLong(), anyLong()))
                .thenThrow(new ForbiddenException("방장 권한이 필요합니다."));

        mockMvc.perform(post("/api/v1/rooms/{id}/transfer-leadership", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_ID + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("POST 400 missing targetUserId — @NotNull triggers VALIDATION, no service call")
    @WithMockUser
    void postTransfer_missingField_returnsValidation() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/transfer-leadership", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        verify(transferService, never()).transferLeadership(any(User.class), anyLong(), anyLong());
    }

    @Test
    @DisplayName("POST 400 zero targetUserId — @Positive triggers VALIDATION")
    @WithMockUser
    void postTransfer_zeroId_returnsValidation() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/{id}/transfer-leadership", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("POST 409 ineligible — service throws IneligibleLeaderException → INELIGIBLE_LEADER")
    @WithMockUser
    void postTransfer_ineligible_returns409() throws Exception {
        when(transferService.transferLeadership(any(User.class), anyLong(), anyLong()))
                .thenThrow(new IneligibleLeaderException("대상의 상태를 확인할 수 없어요."));

        mockMvc.perform(post("/api/v1/rooms/{id}/transfer-leadership", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + TARGET_ID + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INELIGIBLE_LEADER"));
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
