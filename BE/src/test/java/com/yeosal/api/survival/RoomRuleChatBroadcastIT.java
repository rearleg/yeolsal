package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 5.4 AC11 row 4 — full-stack end-to-end coverage for the rule-change
 * chat broadcast. Drives the write through the real {@link RoomRuleService}
 * so the afterCommit synchronization is exercised against a live Postgres
 * (no Mockito-driven shortcut), and asserts both the persisted
 * {@code chat_messages} row and the STOMP frame fan-out via a
 * {@code @SpyBean SimpMessagingTemplate}.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true} so the default
 * {@code ./gradlew test} stays Docker-free, mirroring Story 5.3's
 * {@link com.yeosal.api.room.AutoLeaderPromotionIT} and Story 5.1's
 * {@link RoomRuleNextMonthEvaluatorIT}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RoomRuleChatBroadcastIT {

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

    private static final String SEED_CURRENT_MONTH = "2025-01";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private RoomRuleVersionRepository ruleVersions;
    @Autowired private RoomRuleService roomRuleService;
    @Autowired private ObjectMapper objectMapper;
    @SpyBean private SimpMessagingTemplate messaging;

    @BeforeEach
    void cleanup() {
        reset(messaging);
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "survival_state, streak_freezes, personal_points_ledger, "
                        + "pending_realtime_broadcasts, room_rule_versions, room_point_pool, "
                        + "revival_events, notification_log, chat_messages, daily_entries, "
                        + "reflections, room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("happy — rule edit commits, then chat_messages SYSTEM row appears + STOMP frame fans out")
    void leaderRuleEdit_persistsSystemRowAndPublishesFrame() throws Exception {
        Seed seed = seedRoomWithLeaderAndMember("leader-5-4-it@example.com", "Leader 5.4");

        roomRuleService.updateRule(seed.leader(), seed.room().getId(), "DAILY_UPDATE", false);

        Long systemRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM' "
                        + "AND body LIKE '다음 달부터 새 규칙이 적용됩니다:%'",
                Long.class, seed.room().getId());
        assertThat(systemRowCount).isEqualTo(1L);

        String body = jdbc.queryForObject(
                "SELECT body FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM' "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, seed.room().getId());
        assertThat(body).isEqualTo("다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 제외");

        Long savedRuleVersionId = jdbc.queryForObject(
                "SELECT id FROM room_rule_versions WHERE room_id = ? AND effective_from_month != ?",
                Long.class, seed.room().getId(), SEED_CURRENT_MONTH);
        String rawPayload = jdbc.queryForObject(
                "SELECT payload::text FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM' "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, seed.room().getId());
        JsonNode payload = objectMapper.readTree(rawPayload);
        assertThat(payload.size()).isEqualTo(3);
        assertThat(payload.get("ruleVersionId").asText()).isEqualTo(String.valueOf(savedRuleVersionId));
        assertThat(payload.get("preview").asText()).isEqualTo("매일 업데이트, 주말 제외");
        assertThat(payload.get("effectiveFromMonth").asText()).isNotEqualTo(SEED_CURRENT_MONTH);

        verify(messaging, timeout(2_000).atLeastOnce())
                .convertAndSend(eq("/topic/rooms." + seed.room().getId() + ".chat"), any(Object.class));
    }

    @Test
    @DisplayName("AC5 replace — same nextMonth re-edit appends a second SYSTEM row (not deduped)")
    void leaderRuleEditTwice_appendsTwoSystemRows() {
        Seed seed = seedRoomWithLeaderAndMember("leader-5-4-replace@example.com", "Leader Replace");

        roomRuleService.updateRule(seed.leader(), seed.room().getId(), "DAILY_UPDATE", true);
        roomRuleService.updateRule(seed.leader(), seed.room().getId(), "DAILY_UPDATE", false);

        Long systemRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM' "
                        + "AND body LIKE '다음 달부터 새 규칙이 적용됩니다:%'",
                Long.class, seed.room().getId());
        assertThat(systemRowCount).isEqualTo(2L);

        List<String> bodies = jdbc.queryForList(
                "SELECT body FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM' "
                        + "ORDER BY id ASC",
                String.class, seed.room().getId());
        assertThat(bodies).containsExactly(
                "다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 포함",
                "다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 제외");

        verify(messaging, atLeastOnce())
                .convertAndSend(eq("/topic/rooms." + seed.room().getId() + ".chat"), any(Object.class));
    }

    // ---------- helpers ----------

    private Seed seedRoomWithLeaderAndMember(String email, String nickname) {
        User leader = users.save(new User(email, nickname, "h", AuthProvider.EMAIL));
        User member = users.save(new User("peer-" + email, "Peer", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("rule-broadcast IT room", leader));
        Instant joinedAt = Instant.parse("2026-06-01T00:00:00Z");
        seedMembership(room, leader, RoomRole.OWNER, joinedAt);
        seedMembership(room, member, RoomRole.MEMBER, joinedAt);
        ruleVersions.save(buildRuleRow(room.getId(), SEED_CURRENT_MONTH, true, leader.getId()));
        return new Seed(leader, room);
    }

    private RoomMember seedMembership(Room room, User user, RoomRole role, Instant joinedAt) {
        return roomMembers.save(new RoomMember(room, user, role));
    }

    private RoomRuleVersion buildRuleRow(long roomId, String month, boolean weekendInclude, long actor) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("preset", "DAILY_UPDATE");
        payload.put("weekendInclude", weekendInclude);
        return new RoomRuleVersion(roomId, month, payload, actor);
    }

    private record Seed(User leader, Room room) {}
}
