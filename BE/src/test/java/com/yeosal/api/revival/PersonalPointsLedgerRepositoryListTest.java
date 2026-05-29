package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 3.4 AC8 / BE-7 — Testcontainers IT for
 * {@link PersonalPointsLedgerRepository#findByUserIdAndRoomIdOrderByOccurredAtDesc}.
 * Five cases enumerated in AC8:
 * <ol>
 *   <li>Empty ledger → empty list.</li>
 *   <li>3 rows mixed reasons → returned ordered DESC by occurredAt.</li>
 *   <li>Same occurredAt on two rows → id DESC tiebreaker fires.</li>
 *   <li>Cross-room filter — rows from another room are NOT returned.</li>
 *   <li>Cross-user filter — rows for another user are NOT returned.</li>
 * </ol>
 *
 * <p>{@code @SpringBootTest + @EnabledIfSystemProperty} mirrors
 * {@link MeSurvivalFreeTicketIT}: project rule forbids H2, so repository
 * slice tests are opt-in Testcontainers ITs gated by
 * {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class PersonalPointsLedgerRepositoryListTest {

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

    private static final Instant T0 = Instant.parse("2026-05-22T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-22T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-22T02:00:00Z");
    private static final Instant TIE = Instant.parse("2026-05-22T03:00:00Z");

    @Autowired private PersonalPointsLedgerRepository ledger;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "survival_state, streak_freezes, personal_points_ledger, "
                        + "pending_realtime_broadcasts, room_rule_versions, room_point_pool, "
                        + "revival_events, friendships, "
                        + "notification_log, chat_messages, daily_entries, reflections, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("AC8-1 — empty ledger → empty list")
    void empty_returnsEmpty() {
        User u = seedUser("solo");
        long roomId = seedRoomDirect(u);

        List<PersonalPointsLedger> rows =
                ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(u.getId(), roomId);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("AC8-2 — three rows mixed reasons → DESC by occurredAt")
    void mixedReasons_returnedDesc() {
        User u = seedUser("user");
        long roomId = seedRoomDirect(u);

        tx.executeWithoutResult(t -> {
            ledger.save(new PersonalPointsLedger(u.getId(), roomId, (short) 1,
                    LedgerReason.SURVIVAL, T0, null));
            ledger.save(new PersonalPointsLedger(u.getId(), roomId, (short) -3,
                    LedgerReason.REVIVAL_SPEND, T1, 100L));
            ledger.save(new PersonalPointsLedger(u.getId(), roomId, (short) -5,
                    LedgerReason.FRIEND_GIFT_SPEND, T2, 101L));
        });

        List<PersonalPointsLedger> rows =
                ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(u.getId(), roomId);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(PersonalPointsLedger::getOccurredAt)
                .containsExactly(T2, T1, T0);
        assertThat(rows).extracting(PersonalPointsLedger::getReason)
                .containsExactly(LedgerReason.FRIEND_GIFT_SPEND,
                        LedgerReason.REVIVAL_SPEND, LedgerReason.SURVIVAL);
    }

    @Test
    @DisplayName("AC8-3 — same occurredAt on two rows → id DESC tiebreaker fires")
    void sameOccurredAt_idDescTiebreaker() {
        User u = seedUser("user");
        long roomId = seedRoomDirect(u);

        // Save in deterministic order; ids increment per insert.
        PersonalPointsLedger first = tx.execute(t -> ledger.save(new PersonalPointsLedger(
                u.getId(), roomId, (short) 1, LedgerReason.SURVIVAL, TIE, null)));
        PersonalPointsLedger second = tx.execute(t -> ledger.save(new PersonalPointsLedger(
                u.getId(), roomId, (short) 2, LedgerReason.ADJUSTMENT, TIE, null)));

        List<PersonalPointsLedger> rows =
                ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(u.getId(), roomId);

        assertThat(rows).hasSize(2);
        // Higher id first under the DESC tiebreaker.
        assertThat(rows.get(0).getId()).isEqualTo(second.getId());
        assertThat(rows.get(1).getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("AC8-4 — rows from a different room are NOT returned")
    void crossRoom_excluded() {
        User u = seedUser("user");
        long roomA = seedRoomDirect(u);
        long roomB = seedRoomDirect(u);

        tx.executeWithoutResult(t -> {
            ledger.save(new PersonalPointsLedger(u.getId(), roomA, (short) 1,
                    LedgerReason.SURVIVAL, T0, null));
            ledger.save(new PersonalPointsLedger(u.getId(), roomB, (short) 1,
                    LedgerReason.SURVIVAL, T1, null));
        });

        List<PersonalPointsLedger> rowsA =
                ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(u.getId(), roomA);

        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.get(0).getRoomId()).isEqualTo(roomA);
    }

    @Test
    @DisplayName("AC8-5 — rows for a different user are NOT returned (privacy invariant)")
    void crossUser_excluded() {
        User userA = seedUser("a");
        User userB = seedUser("b");
        long roomId = seedRoomDirect(userA);

        tx.executeWithoutResult(t -> {
            ledger.save(new PersonalPointsLedger(userA.getId(), roomId, (short) 1,
                    LedgerReason.SURVIVAL, T0, null));
            ledger.save(new PersonalPointsLedger(userB.getId(), roomId, (short) 7,
                    LedgerReason.ADJUSTMENT, T1, null));
        });

        List<PersonalPointsLedger> rowsB =
                ledger.findByUserIdAndRoomIdOrderByOccurredAtDesc(userB.getId(), roomId);

        assertThat(rowsB).hasSize(1);
        assertThat(rowsB.get(0).getUserId()).isEqualTo(userB.getId());
        assertThat(rowsB.get(0).getDelta()).isEqualTo((short) 7);
    }

    // ----- helpers -----

    private User seedUser(String tag) {
        return users.save(new User(
                tag + "-" + Instant.now().toEpochMilli() + "-" + Math.random() + "@example.com",
                tag, "h", AuthProvider.EMAIL));
    }

    /**
     * Inserts a minimal {@code rooms} row via SQL — the ledger query only
     * needs the {@code room_id} FK to resolve; spinning up the full Room
     * entity + RoomService scaffold for these repository-only tests is
     * unnecessary noise.
     */
    private long seedRoomDirect(User owner) {
        jdbc.update(
                "insert into rooms(name, owner_id, member_cap) values (?, ?, ?)",
                "Room-" + Math.random(), owner.getId(), 5);
        return jdbc.queryForObject(
                "select id from rooms where owner_id = ? order by id desc limit 1",
                Long.class, owner.getId());
    }
}
