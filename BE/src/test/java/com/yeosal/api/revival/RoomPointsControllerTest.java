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
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
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
 * Story 4.1 BE-8 — web slice for {@link RoomPointsController} covering
 * the five AC5 cases:
 *
 * <ol>
 *   <li>Member, pool total=13 → 200 OK, {@code data.total=13},
 *       {@code data.lastEventAt} forwarded.</li>
 *   <li>Member, missing pool row → 200 OK, defensive coalesce to
 *       {@code data.total=0, data.lastEventAt=null} (legacy rooms that
 *       pre-date V11 backfill).</li>
 *   <li>Non-member → 403 FORBIDDEN with {@code code = "FORBIDDEN"}
 *       (member-guard fires before the pool lookup — same precedent as
 *       ChatController, never leaks room existence).</li>
 *   <li>Missing room id → also 403 (member-guard cannot match a
 *       non-existent room, so the result is identical to "non-member").</li>
 *   <li>Unauthenticated → 4xx (TestSecurityConfig requires
 *       authentication; the real chain would surface 401).</li>
 * </ol>
 *
 * <p>Mirrors the {@link MePersonalPointsLedgerControllerTest} slice shape
 * — JwtAuthenticationFilter + RateLimitFilter excluded so the test stays
 * a pure web layer assertion.
 */
@WebMvcTest(
        value = RoomPointsController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        RoomPointsControllerTest.TestSecurityConfig.class
})
class RoomPointsControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final long ROOM_ID = 42L;
    private static final Instant LAST_EVENT_AT = Instant.parse("2026-05-29T03:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private RoomPointPoolRepository roomPointPool;
    @MockBean private RoomMemberRepository roomMembers;
    @MockBean private CurrentUser currentUser;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("AC5 happy — member, pool total=13 → 200 OK + DTO fields")
    @WithMockUser
    void getPoints_member_returnsCurrentTotal() throws Exception {
        when(roomMembers.existsByRoomIdAndUserId(eq(ROOM_ID), eq(VIEWER_USER_ID)))
                .thenReturn(true);
        when(roomPointPool.findById(eq(ROOM_ID)))
                .thenReturn(Optional.of(poolWithTotal(13, LAST_EVENT_AT)));

        mockMvc.perform(get("/api/v1/rooms/{id}/points", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value((int) ROOM_ID))
                .andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.lastEventAt").exists());
    }

    @Test
    @DisplayName("AC5 — member, missing pool row → 200 OK + total=0, lastEventAt=null")
    @WithMockUser
    void getPoints_member_missingPoolRow_returnsZero() throws Exception {
        when(roomMembers.existsByRoomIdAndUserId(eq(ROOM_ID), eq(VIEWER_USER_ID)))
                .thenReturn(true);
        when(roomPointPool.findById(eq(ROOM_ID))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/rooms/{id}/points", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value((int) ROOM_ID))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.lastEventAt").doesNotExist());
    }

    @Test
    @DisplayName("AC5 — non-member → 403 FORBIDDEN with code=FORBIDDEN")
    @WithMockUser
    void getPoints_nonMember_returnsForbidden() throws Exception {
        when(roomMembers.existsByRoomIdAndUserId(eq(ROOM_ID), eq(VIEWER_USER_ID)))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/rooms/{id}/points", ROOM_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("AC5 — non-existent room id → also 403 (member-guard fires first, no existence leak)")
    @WithMockUser
    void getPoints_missingRoom_returnsForbidden() throws Exception {
        long nonExistentRoom = 9999L;
        when(roomMembers.existsByRoomIdAndUserId(eq(nonExistentRoom), eq(VIEWER_USER_ID)))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/rooms/{id}/points", nonExistentRoom))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("AC5 — unauthenticated → 4xx (TestSecurityConfig anyRequest().authenticated())")
    void getPoints_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/{id}/points", ROOM_ID))
                .andExpect(status().is4xxClientError());
    }

    // ----- helpers -----

    private static RoomPointPool poolWithTotal(int total, Instant lastEventAt) {
        RoomPointPool p = new RoomPointPool(ROOM_ID, total);
        setField(p, "lastEventAt", lastEventAt);
        return p;
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
