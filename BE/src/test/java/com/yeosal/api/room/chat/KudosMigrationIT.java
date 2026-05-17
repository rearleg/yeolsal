package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 3.5 AC1 + AC11 — V12 migration smoke. Proves the partial unique
 * index exists with the IMMUTABLE Asia/Seoul KST expression, that the
 * CHECK constraint accepts {@code 'KUDOS'}, and that the dedupe contract
 * fires on duplicate (sender, target, KST day) inserts while permitting
 * different-day and different-pair inserts.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true} (project-context rule:
 * H2 forbidden — partial unique indexes + jsonb require Postgres).
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class KudosMigrationIT {

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
    @Autowired private TransactionTemplate tx;
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
    @DisplayName("V12 index exists with the IMMUTABLE Asia/Seoul expression")
    void indexExistsWithKstExpression() {
        String indexDef = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'ux_kudos_one_per_day'",
                String.class);

        assertThat(indexDef).isNotNull();
        // Proves the IMMUTABLE-cast contract — PR #57 trap defended.
        assertThat(indexDef).contains("at time zone 'Asia/Seoul'");
        // Predicate is the partial-index gate that matches the ON CONFLICT clause.
        assertThat(indexDef).containsIgnoringCase("where (kind");
    }

    @Test
    @DisplayName("CHECK constraint widened — kind='KUDOS' insert succeeds (V12 widens chk_chat_messages_kind)")
    void kudosKindInsertSucceeds() {
        Fixture f = seed();

        int updated = jdbc.update(
                "insert into chat_messages (room_id, sender_user_id, kind, body, payload) "
                        + "values (?, ?, 'KUDOS', ?, cast(? as jsonb))",
                f.room.getId(), f.sender.getId(),
                "alice이 응원을 보냈어요",
                "{\"senderUserId\":\"" + f.sender.getId()
                        + "\",\"targetUserId\":\"" + f.target.getId()
                        + "\",\"message\":\"\"}");
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("duplicate same-day (sender, target) kudos — 23505 with constraint=ux_kudos_one_per_day")
    void duplicateSameDayKudosRejected() {
        Fixture f = seed();
        insertKudos(f.room.getId(), f.sender.getId(), f.target.getId());

        assertThatThrownBy(() ->
                insertKudos(f.room.getId(), f.sender.getId(), f.target.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_kudos_one_per_day");
    }

    @Test
    @DisplayName("same (sender, target) on different KST day — succeeds (index keys on KST date, not raw ts)")
    void differentKstDaySucceeds() {
        Fixture f = seed();
        insertKudos(f.room.getId(), f.sender.getId(), f.target.getId());
        // Push the first row back so the second row lands on a different KST date.
        jdbc.update(
                "update chat_messages set created_at = now() - interval '2 days' "
                        + "where kind = 'KUDOS' and sender_user_id = ?",
                f.sender.getId());

        // The previous row is now 2 days old in KST; second insert lands today
        // → distinct KST date → no conflict.
        int affected = insertKudos(f.room.getId(), f.sender.getId(), f.target.getId());
        assertThat(affected).isEqualTo(1);
    }

    @Test
    @DisplayName("same calendar day, different pair (sender, otherTarget) — succeeds (index keys on triple)")
    void differentPairSameDaySucceeds() {
        Fixture f = seed();
        User otherTarget = users.save(new User(
                "carol@example.com", "carol", "h", AuthProvider.EMAIL));

        insertKudos(f.room.getId(), f.sender.getId(), f.target.getId());
        int affected = insertKudos(f.room.getId(), f.sender.getId(), otherTarget.getId());

        assertThat(affected).isEqualTo(1);
    }

    // ----- helpers -----

    private record Fixture(User sender, User target, Room room) {}

    private Fixture seed() {
        User owner = users.save(new User(
                "owner-kudos@example.com", "owner", "h", AuthProvider.EMAIL));
        User sender = users.save(new User(
                "alice@example.com", "alice", "h", AuthProvider.EMAIL));
        User target = users.save(new User(
                "bob@example.com", "bob", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("kudos-it room", owner));
        return new Fixture(sender, target, room);
    }

    /** Returns rows affected (1 on insert, 0 if the ON CONFLICT short-circuits). */
    private int insertKudos(long roomId, long senderId, long targetId) {
        return tx.execute(t -> jdbc.update(
                "insert into chat_messages (room_id, sender_user_id, kind, body, payload) "
                        + "values (?, ?, 'KUDOS', ?, cast(? as jsonb))",
                roomId, senderId,
                "alice이 응원을 보냈어요",
                "{\"senderUserId\":\"" + senderId
                        + "\",\"targetUserId\":\"" + targetId
                        + "\",\"message\":\"\"}"));
    }
}
