package com.yeosal.api.kakaoshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Story 6.1 — opt-in integration coverage for the end-to-end Kakao share
 * preview pipeline. Mirrors the existing {@link com.yeosal.api.room.RoomControllerIT}
 * gate: real Postgres via Testcontainers, real Batik PNG transcode, real
 * advisory-lock. Disabled by default so {@code ./gradlew test} stays
 * Docker-free; PR-CI enables it via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class PreviewCardEndToEndIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("yeosal")
                    .withUsername("yeosal")
                    .withPassword("yeosal");

    @TempDir
    static Path previewDir;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("yeosal.share.preview-cards-dir", previewDir::toString);
        registry.add("yeosal.share.preview-card-base", () -> "http://test.local/yeolsal");
        registry.add("yeosal.share.deeplink-base", () -> "https://yeolsal.app");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private User alice;

    @BeforeEach
    void setUp() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "room_invite_preview_cache, room_invites, "
                        + "survival_state, room_rule_versions, room_point_pool, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
        alice = users.save(new User(
                "alice-preview@example.com", "Alice", "hash", AuthProvider.EMAIL));
    }

    @Test
    @DisplayName("POST invite returns share payload, GET preview-card 302-redirects to a PNG URL")
    void freshInvite_returnsSharePayload_with302PointingToFreshlyRenderedPng() throws Exception {
        long roomId = createRoom("방-share");

        MvcResult inviteResult = mockMvc.perform(post("/api/v1/rooms/" + roomId + "/invites")
                        .with(authentication(authFor(alice))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").exists())
                .andExpect(jsonPath("$.data.kakaoShareUrl").exists())
                .andExpect(jsonPath("$.data.previewCardImageUrl").exists())
                .andReturn();

        JsonNode invite = objectMapper.readTree(
                inviteResult.getResponse().getContentAsString()).path("data");
        assertThat(invite.path("kakaoShareUrl").asText()).startsWith("https://yeolsal.app/join?code=");
        assertThat(invite.path("previewCardImageUrl").asText())
                .isEqualTo("http://test.local/yeolsal/api/v1/rooms/" + roomId + "/invites/preview-card");

        // No auth required for the preview-card endpoint.
        mockMvc.perform(get("/api/v1/rooms/" + roomId + "/invites/preview-card"))
                .andExpect(status().isFound());
    }

    private long createRoom(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/rooms")
                        .with(authentication(authFor(alice)))
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"minDailyGoalDays\":10,\"maxMembers\":12}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
        return envelope.path("data").path("id").asLong();
    }

    private static Authentication authFor(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail());
        return new UsernamePasswordAuthenticationToken(
                principal, "", principal.getAuthorities());
    }
}
