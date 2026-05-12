package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.test.context.support.WithMockUser;
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

    private User alice;

    @BeforeEach
    void seedUser() {
        alice = users.save(new User(
                "alice-it@example.com", "Alice", "hash", AuthProvider.EMAIL));
    }

    @Test
    @DisplayName("POST /rooms with maxMembers=12 persists the room and a survival_state row for the owner")
    @WithMockUser(username = "alice-it@example.com")
    void createPersistsRoomAndSurvivalState() throws Exception {
        Instant before = Instant.now();
        Map<String, Object> body = Map.of(
                "name", "방12",
                "minDailyGoalDays", 10,
                "maxMembers", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/rooms")
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
        // grace_ends_at lives ~14 days in the future; the bounds are loose
        // because the service uses Clock and Instant.now() races slightly.
        assertThat(row.getGraceEndsAt())
                .isBetween(before.plus(Duration.ofDays(13)), before.plus(Duration.ofDays(15)));
    }

    @Test
    @DisplayName("POST /rooms with maxMembers=1 rejects with 400 (below FR-8.1.1 floor)")
    @WithMockUser(username = "alice-it@example.com")
    void createRejectsMaxMembersBelow2() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "방1",
                "minDailyGoalDays", 10,
                "maxMembers", 1);

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /rooms with maxMembers=31 rejects with 400 (above FR-8.1.1 ceiling)")
    @WithMockUser(username = "alice-it@example.com")
    void createRejectsMaxMembersAbove30() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "방31",
                "minDailyGoalDays", 10,
                "maxMembers", 31);

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /rooms with maxMembers omitted defaults to 12 (AC8 default per V11)")
    @WithMockUser(username = "alice-it@example.com")
    void createDefaultsMaxMembersTo12() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "방기본",
                "minDailyGoalDays", 10);

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxMembers").value(12));
    }
}
