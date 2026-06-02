package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration test for {@code POST /api/v1/rooms} covering AC1, AC2,
 * AC7, and AC8 of Story 1.1. Opt-in via {@code -Dyeosal.boot-smoke=true} so the
 * regular {@code ./gradlew test} cycle stays fast and Docker-free, mirroring
 * {@link com.yeosal.api.ApplicationBootSmokeTest}.
 *
 * <p>Project-context rule: H2 is forbidden (partial unique expression indexes
 * and jsonb don't behave correctly). Always use Testcontainers Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RoomControllerIT {

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
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private JdbcTemplate jdbc;

    private User alice;

    /**
     * Wipe the schema and re-seed Alice between tests. The Testcontainers
     * container is class-scoped, so without an explicit truncate the second
     * test's {@code users.save(...)} collides with the first test's row on
     * {@code users.email} UNIQUE (Story 1.3 review finding #4 — same defect
     * class, applied to this older sibling IT).
     */
    @BeforeEach
    void setUp() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "survival_state, streak_freezes, personal_points_ledger, "
                        + "pending_realtime_broadcasts, room_rule_versions, room_point_pool, "
                        + "notification_log, chat_messages, daily_entries, reflections, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
        alice = users.save(new User(
                "alice-it@example.com", "Alice", "hash", AuthProvider.EMAIL));
    }

    @Test
    @DisplayName("POST /rooms with maxMembers=12 persists the room and a survival_state row for the owner")
    void createPersistsRoomAndSurvivalState() throws Exception {
        Instant before = Instant.now();
        Map<String, Object> body = Map.of(
                "name", "방12",
                "minDailyGoalDays", 10,
                "maxMembers", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/rooms")
                        .with(authentication(authFor(alice)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxMembers").value(12))
                .andExpect(jsonPath("$.data.ownerId").value(alice.getId()))
                .andReturn();

        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
        long roomId = envelope.path("data").path("id").asLong();
        Optional<SurvivalState> survival =
                survivalStates.findByRoomIdAndUserId(roomId, alice.getId());
        assertThat(survival).as("survival_state row written atomically with the room").isPresent();
        SurvivalState row = survival.orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        Integer ruleCount = jdbc.queryForObject(
                "select count(*) from room_rule_versions where room_id = ?",
                Integer.class,
                roomId);
        assertThat(ruleCount).as("fresh rooms receive a default current-month rule").isEqualTo(1);
        // grace_ends_at lives ~14 days in the future; the bounds are loose
        // because the service uses Clock and Instant.now() races slightly.
        assertThat(row.getGraceEndsAt())
                .isBetween(before.plus(Duration.ofDays(13)), before.plus(Duration.ofDays(15)));
    }

    @Test
    @DisplayName("POST /rooms with maxMembers=1 rejects with 400 (below FR-8.1.1 floor)")
    void createRejectsMaxMembersBelow2() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "방1",
                "minDailyGoalDays", 10,
                "maxMembers", 1);

        mockMvc.perform(post("/api/v1/rooms")
                        .with(authentication(authFor(alice)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /rooms with maxMembers=31 rejects with 400 (above FR-8.1.1 ceiling)")
    void createRejectsMaxMembersAbove30() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "방31",
                "minDailyGoalDays", 10,
                "maxMembers", 31);

        mockMvc.perform(post("/api/v1/rooms")
                        .with(authentication(authFor(alice)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /rooms with maxMembers omitted defaults to 12 (AC8 default per V11)")
    void createDefaultsMaxMembersTo12() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "방기본",
                "minDailyGoalDays", 10);

        mockMvc.perform(post("/api/v1/rooms")
                        .with(authentication(authFor(alice)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxMembers").value(12));
    }

    /**
     * Build a {@link UserPrincipal}-backed {@link Authentication} matching the
     * production {@code CurrentUser.require(...)} contract. Plain
     * {@code @WithMockUser} produces a Spring Security {@code User} principal
     * which fails the {@code instanceof UserPrincipal} guard and 401s before
     * exercising controller behavior (Story 1.3 review finding #2).
     */
    private static Authentication authFor(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail());
        return new UsernamePasswordAuthenticationToken(
                principal, "", principal.getAuthorities());
    }
}
