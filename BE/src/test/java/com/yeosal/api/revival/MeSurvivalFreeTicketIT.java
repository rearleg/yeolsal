package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 3.1 AC1 — signup/default invariant for the lifetime-one free
 * revival ticket flag (V11 step 2).
 *
 * <p>Proves that a fresh {@link User} saved via the standard repository
 * path lands with {@code free_revival_ticket_used = false}. The V11
 * column default carries the invariant; this test guards against future
 * regressions in signup code paths that might explicitly null/override
 * the field on insert.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}, mirroring the other
 * Testcontainers ITs (project-context rule: H2 forbidden).
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class MeSurvivalFreeTicketIT {

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

    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

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
    @DisplayName("AC1 — fresh user.save() lands with free_revival_ticket_used = false (V11 default)")
    void freshUser_landsWithFreeTicketUnused() {
        User u = users.save(new User(
                "fresh@example.com", "Fresh", "hash", AuthProvider.EMAIL));
        User refreshed = users.findById(u.getId()).orElseThrow();

        assertThat(refreshed.isFreeRevivalTicketUsed()).isFalse();

        // Also confirm the V11 column default fired at the DB layer rather
        // than the JPA field initializer alone — fetches the raw column.
        Boolean stored = jdbc.queryForObject(
                "select free_revival_ticket_used from users where id = ?",
                Boolean.class, u.getId());
        assertThat(stored).isFalse();
    }

    @Test
    @DisplayName("UserRepository.markFreeTicketUsed flips the flag exactly once (atomic check-and-set)")
    void markFreeTicketUsed_flipsExactlyOnce() {
        User u = users.save(new User(
                "ticket@example.com", "Ticket", "hash", AuthProvider.EMAIL));

        // @Modifying JPQL needs an enclosing transaction — Spring Data's
        // SimpleJpaRepository auto-wraps save/find/delete but NOT custom
        // @Modifying queries.
        int first = tx.execute(t -> users.markFreeTicketUsed(u.getId()));
        int second = tx.execute(t -> users.markFreeTicketUsed(u.getId()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(users.findById(u.getId()).orElseThrow().isFreeRevivalTicketUsed()).isTrue();
    }
}
