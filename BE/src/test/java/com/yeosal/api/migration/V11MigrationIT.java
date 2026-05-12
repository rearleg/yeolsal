package com.yeosal.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 1.4 — V11 migration verification + production-backfill contract.
 * Opt-in via {@code -Dyeosal.boot-smoke=true}, mirroring {@link
 * com.yeosal.api.room.RoomControllerIT}, {@link
 * com.yeosal.api.survival.SurvivalStateEvaluatorIT}, and {@link
 * com.yeosal.api.survival.SurvivalStateRosterIT}.
 *
 * <p>Deliberately NOT a {@code @SpringBootTest}: the contract under test is
 * Flyway/SQL behavior, not the Spring context. A plain JUnit 5 +
 * Testcontainers + Flyway-programmatic + {@link JdbcTemplate} harness keeps
 * per-test startup cheap so each method can DROP/CREATE the {@code public}
 * schema and replay V1..V11 (or V1..V10 then V11) from scratch.
 *
 * <p>The replay tests (AC5/AC6) feed the V11 SQL through {@link
 * JdbcTemplate#execute(String)} directly — bypassing Flyway's
 * {@code flyway_schema_history} guard — to prove every statement in V11 is
 * safe to re-execute against a database that already has V11 applied.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class V11MigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("yeosal")
                    .withUsername("yeosal")
                    .withPassword("yeosal");

    private DataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetSchema() {
        ds = newDataSource();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("GRANT ALL ON SCHEMA public TO " + POSTGRES.getUsername());
        jdbc.execute("GRANT ALL ON SCHEMA public TO PUBLIC");
    }

    @Test
    @DisplayName("v11_appliesCleanly_onFreshEmptyPostgres (AC1)")
    void v11_appliesCleanly_onFreshEmptyPostgres() {
        flywayAll().migrate();

        Map<String, Object> v11 = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '11'");
        assertThat(v11.get("success")).isEqualTo(true);

        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failed).isZero();

        String topVersion = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(topVersion).isEqualTo("11");
    }

    @Test
    @DisplayName("v11_preservesExistingMaxMembersDuringWiden (AC2)")
    void v11_preservesExistingMaxMembersDuringWiden() {
        flywayUpTo("10").migrate();

        Long owner = insertUser("owner-ac2a@example.com", "Owner");
        Long legacy8 = insertRoom("legacy-8", owner, 8, 10);
        Long legacy5 = insertRoom("legacy-5", owner, 5, 10);

        flywayAll().migrate();

        Integer cap8 = jdbc.queryForObject(
                "SELECT max_members FROM rooms WHERE id = ?", Integer.class, legacy8);
        Integer cap5 = jdbc.queryForObject(
                "SELECT max_members FROM rooms WHERE id = ?", Integer.class, legacy5);
        assertThat(cap8).isEqualTo(8);
        assertThat(cap5).isEqualTo(5);

        String defaultExpr = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'rooms' AND column_name = 'max_members'",
                String.class);
        assertThat(defaultExpr).contains("12");
    }

    @Test
    @DisplayName("v11_widensCheckConstraint (AC2)")
    void v11_widensCheckConstraint() {
        flywayAll().migrate();
        Long owner = insertUser("owner-ac2b@example.com", "Owner");

        Long big = insertRoom("big-30", owner, 30, 10);
        assertThat(big).isNotNull();

        assertThatThrownBy(() -> insertRoom("too-big-31", owner, 31, 10))
                .hasMessageContaining("chk_rooms_max_members");
        assertThatThrownBy(() -> insertRoom("too-small-1", owner, 1, 10))
                .hasMessageContaining("chk_rooms_max_members");
    }

    @Test
    @DisplayName("v11_backfills_survival_state_for_every_legacy_member (AC3)")
    void v11_backfills_survival_state_for_every_legacy_member() {
        flywayUpTo("10").migrate();

        Long owner = insertUser("owner-ac3a@example.com", "Owner");
        Long u1 = insertUser("u1-ac3a@example.com", "U1");
        Long u2 = insertUser("u2-ac3a@example.com", "U2");
        Long room = insertRoom("legacy-3", owner, 8, 10);
        insertMember(room, owner, "OWNER");
        insertMember(room, u1, "MEMBER");
        insertMember(room, u2, "MEMBER");

        flywayAll().migrate();

        Integer ss = jdbc.queryForObject(
                "SELECT count(*) FROM survival_state", Integer.class);
        Integer rm = jdbc.queryForObject(
                "SELECT count(*) FROM room_members", Integer.class);
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM survival_state WHERE status = 'ACTIVE'",
                Integer.class);
        Integer graceNull = jdbc.queryForObject(
                "SELECT count(*) FROM survival_state WHERE grace_ends_at IS NULL",
                Integer.class);
        Integer eliminatedNull = jdbc.queryForObject(
                "SELECT count(*) FROM survival_state WHERE eliminated_at IS NULL",
                Integer.class);
        Integer broadNull = jdbc.queryForObject(
                "SELECT count(*) FROM survival_state WHERE broad_visibility_at IS NULL",
                Integer.class);
        assertThat(ss).isEqualTo(rm).isEqualTo(3);
        assertThat(active).isEqualTo(3);
        assertThat(graceNull).isEqualTo(3);
        assertThat(eliminatedNull).isEqualTo(3);
        assertThat(broadNull).isEqualTo(3);
    }

    @Test
    @DisplayName("v11_backfills_room_rule_versions_for_every_legacy_room (AC3)")
    void v11_backfills_room_rule_versions_for_every_legacy_room() {
        flywayUpTo("10").migrate();

        Long owner = insertUser("owner-ac3b@example.com", "Owner");
        insertRoom("rule-a", owner, 8, 10);
        insertRoom("rule-b", owner, 8, 10);

        flywayAll().migrate();

        Integer rrv = jdbc.queryForObject(
                "SELECT count(*) FROM room_rule_versions", Integer.class);
        Integer rooms = jdbc.queryForObject(
                "SELECT count(*) FROM rooms", Integer.class);
        assertThat(rrv).isEqualTo(rooms).isEqualTo(2);

        String expectedMonth = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Integer monthMatch = jdbc.queryForObject(
                "SELECT count(*) FROM room_rule_versions WHERE effective_from_month = ?",
                Integer.class, expectedMonth);
        assertThat(monthMatch).isEqualTo(2);

        Integer payloadMatch = jdbc.queryForObject(
                "SELECT count(*) FROM room_rule_versions "
                        + "WHERE rule_payload @> "
                        + "'{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":true}'::jsonb",
                Integer.class);
        assertThat(payloadMatch).isEqualTo(2);
    }

    @Test
    @DisplayName("v11_backfills_room_point_pool_for_every_legacy_room (AC3)")
    void v11_backfills_room_point_pool_for_every_legacy_room() {
        flywayUpTo("10").migrate();

        Long owner = insertUser("owner-ac3c@example.com", "Owner");
        insertRoom("pool-a", owner, 8, 10);
        insertRoom("pool-b", owner, 8, 10);

        flywayAll().migrate();

        Integer rpp = jdbc.queryForObject(
                "SELECT count(*) FROM room_point_pool", Integer.class);
        Integer rooms = jdbc.queryForObject(
                "SELECT count(*) FROM rooms", Integer.class);
        Integer zeroTotal = jdbc.queryForObject(
                "SELECT count(*) FROM room_point_pool WHERE total = 0", Integer.class);
        Integer lastEventNull = jdbc.queryForObject(
                "SELECT count(*) FROM room_point_pool WHERE last_event_at IS NULL",
                Integer.class);
        assertThat(rpp).isEqualTo(rooms).isEqualTo(2);
        assertThat(zeroTotal).isEqualTo(2);
        assertThat(lastEventNull).isEqualTo(2);
    }

    @Test
    @DisplayName("v11_backfills_free_revival_ticket_for_every_legacy_user (AC4)")
    void v11_backfills_free_revival_ticket_for_every_legacy_user() {
        flywayUpTo("10").migrate();

        for (int i = 1; i <= 5; i++) {
            insertUser("ac4-u" + i + "@example.com", "U" + i);
        }

        flywayAll().migrate();

        Integer nullCount = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE free_revival_ticket_used IS NULL",
                Integer.class);
        Integer falseCount = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE free_revival_ticket_used = false",
                Integer.class);
        assertThat(nullCount).isZero();
        assertThat(falseCount).isEqualTo(5);
    }

    @Test
    @DisplayName("v11_replay_via_jdbc_isIdempotent (AC5)")
    void v11_replay_via_jdbc_isIdempotent() throws Exception {
        flywayAll().migrate();
        Map<String, Integer> before = captureV11RowCounts();

        jdbc.execute(loadV11Sql());

        Map<String, Integer> after = captureV11RowCounts();
        assertThat(after).isEqualTo(before);

        Integer v11ok = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE version = '11' AND success = true",
                Integer.class);
        assertThat(v11ok).isEqualTo(1);
    }

    @Test
    @DisplayName("v11_replay_preservesBackfillRows (AC6)")
    void v11_replay_preservesBackfillRows() throws Exception {
        flywayUpTo("10").migrate();

        Long owner = insertUser("ac6-owner@example.com", "Owner");
        Long u1 = insertUser("ac6-u1@example.com", "U1");
        Long roomA = insertRoom("ac6-a", owner, 8, 10);
        Long roomB = insertRoom("ac6-b", owner, 8, 10);
        insertMember(roomA, owner, "OWNER");
        insertMember(roomA, u1, "MEMBER");
        insertMember(roomB, owner, "OWNER");

        flywayAll().migrate();
        Map<String, Integer> before = captureV11RowCounts();
        assertThat(before.get("survival_state")).isEqualTo(3);
        assertThat(before.get("room_rule_versions")).isEqualTo(2);
        assertThat(before.get("room_point_pool")).isEqualTo(2);

        jdbc.execute(loadV11Sql());

        Map<String, Integer> after = captureV11RowCounts();
        assertThat(after).isEqualTo(before);
    }

    // ----- helpers -----

    private DataSource newDataSource() {
        DriverManagerDataSource src = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        src.setDriverClassName("org.postgresql.Driver");
        return src;
    }

    private Flyway flywayAll() {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
    }

    private Flyway flywayUpTo(String version) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private Long insertUser(String email, String nickname) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, nickname, password_hash, auth_provider, created_at) "
                        + "VALUES (?, ?, 'h', 'EMAIL', now()) RETURNING id",
                Long.class, email, nickname);
    }

    private Long insertRoom(String name, long ownerId, int maxMembers, int minDailyGoalDays) {
        return jdbc.queryForObject(
                "INSERT INTO rooms (name, owner_id, max_members, min_daily_goal_days, created_at) "
                        + "VALUES (?, ?, ?, ?, now()) RETURNING id",
                Long.class, name, ownerId, maxMembers, minDailyGoalDays);
    }

    private void insertMember(long roomId, long userId, String role) {
        jdbc.update(
                "INSERT INTO room_members (room_id, user_id, role, joined_at) "
                        + "VALUES (?, ?, ?, now())",
                roomId, userId, role);
    }

    private String loadV11Sql() throws Exception {
        return new ClassPathResource(
                "db/migration/V11__survival_revival_economy.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Integer> captureV11RowCounts() {
        Map<String, Integer> counts = new HashMap<>();
        List<String> tables = List.of(
                "survival_state",
                "streak_freezes",
                "revival_events",
                "personal_points_ledger",
                "room_point_pool",
                "room_rule_versions",
                "record_visibility_prefs",
                "final_three_posters",
                "room_invite_preview_cache",
                "pending_realtime_broadcasts",
                "users",
                "rooms",
                "room_members");
        for (String t : tables) {
            counts.put(t, jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class));
        }
        return counts;
    }
}
