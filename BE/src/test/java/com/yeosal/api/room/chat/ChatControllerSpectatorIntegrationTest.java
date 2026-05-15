package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 2.1 AC4 / AC10 — full-stack integration test for the spectator
 * chat-write gate. Opt-in via {@code -Dyeosal.boot-smoke=true}, mirroring
 * {@link com.yeosal.api.survival.SurvivalStateRosterIT}.
 *
 * <p>Two cases on the same fixture (truncated between):
 * <ul>
 *   <li>SPECTATOR row → {@code POST /api/v1/rooms/{id}/messages} returns
 *       {@code 403} + {@code error.code == "SPECTATOR_WRITE_FORBIDDEN"}.</li>
 *   <li>ACTIVE row → same payload returns {@code 200} and the message row
 *       is persisted.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class ChatControllerSpectatorIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("yeosal")
                    .withUsername("yeosal")
                    .withPassword("yeosal");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "survival_state, streak_freezes, personal_points_ledger, "
                        + "pending_realtime_broadcasts, room_rule_versions, room_point_pool, "
                        + "notification_log, chat_messages, daily_entries, reflections, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("SPECTATOR member POSTing /rooms/{id}/messages → 403 SPECTATOR_WRITE_FORBIDDEN")
    void spectator_post_messages_returns_403() throws Exception {
        Fixture f = seed(SurvivalStatus.SPECTATOR);

        long beforeCount = countChatMessages();

        mockMvc.perform(post("/api/v1/rooms/" + f.roomId() + "/messages")
                        .with(authentication(authenticate(f.alice())))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"안녕하세요\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code")
                        .value(SpectatorWriteForbiddenException.CODE))
                .andExpect(jsonPath("$.error.message").value("관전 중에는 메시지를 보낼 수 없어요."));

        // No row written despite the request reaching the controller.
        assertThat(countChatMessages()).isEqualTo(beforeCount);
    }

    @Test
    @DisplayName("ACTIVE member POSTing /rooms/{id}/messages → 200 + row persisted (regression for the parent path)")
    void active_post_messages_returns_200_and_persists() throws Exception {
        Fixture f = seed(SurvivalStatus.ACTIVE);

        long beforeCount = countChatMessages();

        mockMvc.perform(post("/api/v1/rooms/" + f.roomId() + "/messages")
                        .with(authentication(authenticate(f.alice())))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"안녕하세요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("안녕하세요"))
                .andExpect(jsonPath("$.data.kind").value("USER"));

        assertThat(countChatMessages()).isEqualTo(beforeCount + 1);
    }

    // ---- fixture helpers ----

    private Fixture seed(SurvivalStatus aliceStatus) {
        return tx.execute(s -> {
            User alice = users.save(new User("alice@example.com", "Alice", "hash", AuthProvider.EMAIL));
            Room room = rooms.save(new Room("Spectator Test Room", alice));
            roomMembers.save(new RoomMember(room, alice, RoomRole.OWNER));

            // @PrePersist on SurvivalState fills lastStateChangeAt to now()
            // when the row is first saved, so we only need to seed status.
            // The setter is package-private (Story 1.1 invariant: transitions
            // go through SurvivalStateService), so we mutate via reflection.
            SurvivalState state = survivalStates.save(
                    new SurvivalState(room, alice, /* graceEndsAt */ null));
            setStatus(state, aliceStatus);
            survivalStates.save(state);

            return new Fixture(alice, room.getId());
        });
    }

    private long countChatMessages() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Long.class);
        return n == null ? 0L : n;
    }

    private static Authentication authenticate(User u) {
        UserPrincipal principal = new UserPrincipal(u.getId(), u.getEmail());
        return new UsernamePasswordAuthenticationToken(
                principal, "n/a", principal.getAuthorities());
    }

    private static void setStatus(SurvivalState state, SurvivalStatus next) {
        try {
            Field f = SurvivalState.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(state, next);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private record Fixture(User alice, long roomId) {}
}
