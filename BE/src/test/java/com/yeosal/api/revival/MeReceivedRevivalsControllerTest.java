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
import java.time.Instant;
import java.util.List;
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
 * Story 3.4 AC8 / BE-7 — web slice for {@link MeReceivedRevivalsController}.
 * Four cases enumerated at AC8:
 * <ol>
 *   <li>Happy path → 200 OK envelope with all 3 source types.</li>
 *   <li>FRIEND_GIFT row → donorNickname present in response.</li>
 *   <li>FREE_TICKET / PERSONAL_POINTS rows → donorNickname null.</li>
 *   <li>Auth absent → 4xx.</li>
 * </ol>
 */
@WebMvcTest(
        value = MeReceivedRevivalsController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, RateLimitFilter.class}))
@Import({
        ApiExceptionHandler.class,
        MeReceivedRevivalsControllerTest.TestSecurityConfig.class
})
class MeReceivedRevivalsControllerTest {

    private static final long VIEWER_USER_ID = 7L;
    private static final long ROOM_ID = 42L;
    private static final long DONOR_ID = 99L;
    private static final Instant T0 = Instant.parse("2026-05-22T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-22T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-22T02:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockBean private RevivalEventRepository revivalEvents;
    @MockBean private UserRepository users;
    @MockBean private RoomRepository rooms;
    @MockBean private CurrentUser currentUser;

    private User viewer;
    private User donor;

    @BeforeEach
    void setUp() {
        viewer = makeUser(VIEWER_USER_ID, "viewer@example.com", "Viewer");
        donor = makeUser(DONOR_ID, "donor@example.com", "정민");
        when(currentUser.require(any(Authentication.class))).thenReturn(viewer);
    }

    @Test
    @DisplayName("AC3 happy — all 3 sources → DESC list, donor nickname only on FRIEND_GIFT")
    @WithMockUser
    void received_threeSources_returnsAllWithDonor() throws Exception {
        RevivalEvent gift = makeRevival(
                1003L, ROOM_ID, VIEWER_USER_ID, DONOR_ID, RevivalSource.FRIEND_GIFT, T2);
        RevivalEvent personal = makeRevival(
                1002L, ROOM_ID, VIEWER_USER_ID, null, RevivalSource.PERSONAL_POINTS, T1);
        RevivalEvent freeT = makeRevival(
                1001L, ROOM_ID, VIEWER_USER_ID, null, RevivalSource.FREE_TICKET, T0);
        when(revivalEvents.findReceivedRevivalsByRoom(eq(VIEWER_USER_ID), eq(ROOM_ID)))
                .thenReturn(List.of(gift, personal, freeT));
        when(users.findAllById(List.of(DONOR_ID))).thenReturn(List.of(donor));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(makeRoom(ROOM_ID, "TestRoom", viewer)));

        mockMvc.perform(get("/api/v1/me/received-revivals").param("roomId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].source").value("FRIEND_GIFT"))
                .andExpect(jsonPath("$.data[0].donorUserId").value((int) DONOR_ID))
                .andExpect(jsonPath("$.data[0].donorNickname").value("정민"))
                .andExpect(jsonPath("$.data[0].roomName").value("TestRoom"))
                .andExpect(jsonPath("$.data[1].source").value("PERSONAL_POINTS"))
                .andExpect(jsonPath("$.data[1].donorUserId").isEmpty())
                .andExpect(jsonPath("$.data[1].donorNickname").isEmpty())
                .andExpect(jsonPath("$.data[2].source").value("FREE_TICKET"))
                .andExpect(jsonPath("$.data[2].donorUserId").isEmpty())
                .andExpect(jsonPath("$.data[2].donorNickname").isEmpty());
    }

    @Test
    @DisplayName("AC3 — empty list when no rows match")
    @WithMockUser
    void received_empty_returnsEmpty() throws Exception {
        when(revivalEvents.findReceivedRevivalsByRoom(eq(VIEWER_USER_ID), eq(ROOM_ID)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/me/received-revivals").param("roomId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("AC3 — FREE_TICKET-only response has null donor fields")
    @WithMockUser
    void received_freeTicketOnly_donorFieldsNull() throws Exception {
        RevivalEvent freeT = makeRevival(
                2001L, ROOM_ID, VIEWER_USER_ID, null, RevivalSource.FREE_TICKET, T0);
        when(revivalEvents.findReceivedRevivalsByRoom(eq(VIEWER_USER_ID), eq(ROOM_ID)))
                .thenReturn(List.of(freeT));
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(makeRoom(ROOM_ID, "TestRoom", viewer)));

        mockMvc.perform(get("/api/v1/me/received-revivals").param("roomId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("FREE_TICKET"))
                .andExpect(jsonPath("$.data[0].donorUserId").isEmpty())
                .andExpect(jsonPath("$.data[0].donorNickname").isEmpty());
    }

    @Test
    @DisplayName("auth absent → 4xx")
    void received_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/me/received-revivals").param("roomId", "42"))
                .andExpect(status().is4xxClientError());
    }

    private static RevivalEvent makeRevival(
            long id, long roomId, long userId, Long giverUserId,
            RevivalSource source, Instant occurredAt) {
        RevivalEvent ev = new RevivalEvent(
                roomId, userId, giverUserId, source,
                source == RevivalSource.FRIEND_GIFT ? "PUSH_INITIATED" : null,
                (short) (source == RevivalSource.FREE_TICKET ? 0
                        : source == RevivalSource.PERSONAL_POINTS ? 3 : 5),
                source == RevivalSource.FREE_TICKET ? 0
                        : source == RevivalSource.PERSONAL_POINTS ? 3 : 5,
                occurredAt.minusSeconds(60), occurredAt);
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
