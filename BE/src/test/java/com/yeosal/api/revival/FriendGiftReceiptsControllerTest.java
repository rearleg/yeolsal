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
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Story 3.2 AC12 — web slice for {@link MeFriendGiftController}. Covers
 * the {@code /me/friend-gift-receipts} (AC7) and
 * {@code /me/has-given-friend-gift} (AC10) endpoints.
 */
@WebMvcTest(
        value = MeFriendGiftController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        FriendGiftReceiptsControllerTest.TestSecurityConfig.class
})
class FriendGiftReceiptsControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final Instant FIXED_NOW = Instant.parse("2026-05-18T03:14:15Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private RevivalEventRepository revivalEvents;
    @MockBean private UserRepository users;
    @MockBean private RoomRepository rooms;
    @MockBean private CurrentUser currentUser;
    @MockBean private Clock clock;

    private User viewer;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
        when(clock.instant()).thenReturn(FIXED_NOW);
    }

    @Test
    @DisplayName("/me/friend-gift-receipts no rows → empty list, 200")
    @WithMockUser
    void receipts_empty_returnsEmptyList() throws Exception {
        when(revivalEvents.findFriendGiftReceiptsWithin7Days(
                eq(VIEWER_USER_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/friend-gift-receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("/me/friend-gift-receipts one row → donor + room resolved, daysSinceRevival=0")
    @WithMockUser
    void receipts_oneRow_returnsOne() throws Exception {
        long roomId = 42L;
        long donorId = 99L;
        Instant occurredAt = FIXED_NOW.minusSeconds(60);
        RevivalEvent row = makeRevivalEvent(1234L, roomId, VIEWER_USER_ID, donorId, occurredAt);
        when(revivalEvents.findFriendGiftReceiptsWithin7Days(
                eq(VIEWER_USER_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(row));
        User donor = makeUser(donorId, "donor@example.com", "정민");
        when(users.findAllById(List.of(donorId))).thenReturn(List.of(donor));
        Room room = makeRoom(roomId, "TestRoom", donor);
        when(rooms.findAllById(List.of(roomId))).thenReturn(List.of(room));

        mockMvc.perform(get("/api/v1/me/friend-gift-receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].revivalEventId").value(1234))
                .andExpect(jsonPath("$.data[0].roomId").value((int) roomId))
                .andExpect(jsonPath("$.data[0].donorUserId").value((int) donorId))
                .andExpect(jsonPath("$.data[0].donorNickname").value("정민"))
                .andExpect(jsonPath("$.data[0].roomName").value("TestRoom"))
                .andExpect(jsonPath("$.data[0].daysSinceRevival").value(0));
    }

    @Test
    @DisplayName("/me/has-given-friend-gift returns {has:false}")
    @WithMockUser
    void hasGiven_false() throws Exception {
        when(revivalEvents.existsFriendGiftSendByGiver(VIEWER_USER_ID, -1L)).thenReturn(false);
        mockMvc.perform(get("/api/v1/me/has-given-friend-gift"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has").value(false));
    }

    @Test
    @DisplayName("/me/has-given-friend-gift returns {has:true}")
    @WithMockUser
    void hasGiven_true() throws Exception {
        when(revivalEvents.existsFriendGiftSendByGiver(VIEWER_USER_ID, -1L)).thenReturn(true);
        mockMvc.perform(get("/api/v1/me/has-given-friend-gift"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has").value(true));
    }

    @Test
    @DisplayName("/me/friend-gift-receipts unauthenticated → 4xx")
    void receipts_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/me/friend-gift-receipts"))
                .andExpect(status().is4xxClientError());
    }

    private static RevivalEvent makeRevivalEvent(
            long id, long roomId, long receiverId, long giverId, Instant occurredAt) {
        RevivalEvent ev = new RevivalEvent(
                roomId, receiverId, giverId, RevivalSource.FRIEND_GIFT, "PUSH_INITIATED",
                (short) 5, 5, occurredAt.minusSeconds(3600), occurredAt);
        setField(ev, "id", id);
        return ev;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner);
        setField(r, "id", id);
        return r;
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
