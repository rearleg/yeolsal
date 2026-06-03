package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 5.2 AC2 — V13 migration smoke. Proves the two new columns exist
 * on the {@code rooms} table and that the paired CHECK constraint
 * {@code chk_rooms_pending_cap_consistency} blocks a half-written state.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class V13MigrationIT {

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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;

    @BeforeEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "survival_state, streak_freezes, personal_points_ledger, "
                        + "pending_realtime_broadcasts, room_rule_versions, room_point_pool, "
                        + "revival_events, "
                        + "notification_log, chat_messages, daily_entries, reflections, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("V13 — rooms.pending_max_members and pending_max_members_effective_from_month exist")
    void v13ColumnsExist() {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name='rooms' "
                        + "and column_name in ('pending_max_members', "
                        + "'pending_max_members_effective_from_month')",
                Integer.class);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("V13 — chk_rooms_pending_cap_consistency blocks half-written state")
    void halfWrittenStateRejected() {
        User owner = users.save(new User(
                "owner-v13@example.com", "owner-v13", "h", AuthProvider.EMAIL));
        Long roomId = rooms.save(new com.yeosal.api.room.Room("v13 room", owner)).getId();

        assertThatThrownBy(() ->
                jdbc.update(
                        "update rooms set pending_max_members = 20, "
                                + "pending_max_members_effective_from_month = null "
                                + "where id = ?",
                        roomId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                jdbc.update(
                        "update rooms set pending_max_members = null, "
                                + "pending_max_members_effective_from_month = '2026-05' "
                                + "where id = ?",
                        roomId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V13 — chk_rooms_pending_cap_month_format blocks malformed month strings")
    void malformedEffectiveMonthRejected() {
        User owner = users.save(new User(
                "owner-v13-format@example.com", "owner-v13-format", "h", AuthProvider.EMAIL));
        Long roomId = rooms.save(new Room("v13 format room", owner)).getId();

        assertThatThrownBy(() ->
                jdbc.update(
                        "update rooms set pending_max_members = 20, "
                                + "pending_max_members_effective_from_month = '2026-13' "
                                + "where id = ?",
                        roomId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                jdbc.update(
                        "update rooms set pending_max_members = 20, "
                                + "pending_max_members_effective_from_month = '2026-1' "
                                + "where id = ?",
                        roomId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
