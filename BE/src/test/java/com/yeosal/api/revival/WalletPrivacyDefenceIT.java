package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 3.4 AC8 / BE-7 — Wallet privacy defence integration test
 * (Testcontainers). Two cases enumerated at AC8:
 * <ol>
 *   <li>User B authenticated → GET /me/personal-points-ledger returns
 *       USER B's data, never User A's (verify via SQL probe).</li>
 *   <li>User B authenticated → GET /me/received-revivals returns USER B's
 *       receipts, never User A's.</li>
 * </ol>
 *
 * <p>Calls the controller bean directly with an {@link Authentication}
 * carrying User B's {@link UserPrincipal} — bypasses the HTTP filter chain
 * because the privacy invariant lives at the controller method signature
 * (no {@code userId} param) and the SQL clause ({@code where user_id =
 * :me}) inside the {@code @Transactional(readOnly = true)} boundary.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class WalletPrivacyDefenceIT {

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

    @Autowired private MePersonalPointsLedgerController ledgerController;
    @Autowired private MeReceivedRevivalsController receivedController;
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
    @DisplayName("AC8-Privacy-1 — User B sees only B's ledger rows (A's are excluded SQL-side)")
    void ledger_userB_doesNotSeeUserA() {
        User userA = seedUser("a");
        User userB = seedUser("b");
        long roomId = seedRoomDirect(userA);

        // A has a sensitive ledger row; B has a different ledger row in the same room.
        tx.executeWithoutResult(t -> {
            ledger.save(new PersonalPointsLedger(userA.getId(), roomId, (short) 99,
                    LedgerReason.ADJUSTMENT, T0, null));
            ledger.save(new PersonalPointsLedger(userB.getId(), roomId, (short) 1,
                    LedgerReason.SURVIVAL, T1, null));
        });

        // Authenticate as B and call the controller method directly.
        var responseB = ledgerController.ledger(authFor(userB), roomId);
        List<LedgerEntryDto> rows = responseB.data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).delta()).isEqualTo((short) 1);
        assertThat(rows.get(0).reason()).isEqualTo(LedgerReason.SURVIVAL.name());

        // Probe directly: A's row is in the DB but invisible to B's request.
        Long aRows = jdbc.queryForObject(
                "select count(*) from personal_points_ledger where user_id = ?",
                Long.class, userA.getId());
        assertThat(aRows).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC8-Privacy-2 — User B sees only B's received revivals (A's are excluded SQL-side)")
    void received_userB_doesNotSeeUserA() {
        User userA = seedUser("a");
        User userB = seedUser("b");
        long roomId = seedRoomDirect(userA);

        // A received a FRIEND_GIFT revival; B received a FREE_TICKET. Both in the same room.
        insertRevival(roomId, userA.getId(), userB.getId(), "FRIEND_GIFT", T0);
        insertRevival(roomId, userB.getId(), null, "FREE_TICKET", T1);

        var responseB = receivedController.received(authFor(userB), roomId);
        List<ReceivedRevivalDto> rows = responseB.data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).source()).isEqualTo("FREE_TICKET");
        assertThat(rows.get(0).donorUserId()).isNull();

        // Probe directly: A's row is in the DB but invisible to B's request.
        Long aRows = jdbc.queryForObject(
                "select count(*) from revival_events where user_id = ?",
                Long.class, userA.getId());
        assertThat(aRows).isEqualTo(1L);
    }

    // ----- helpers -----

    private User seedUser(String tag) {
        return users.save(new User(
                tag + "-" + Instant.now().toEpochMilli() + "-" + Math.random() + "@example.com",
                tag, "h", AuthProvider.EMAIL));
    }

    private long seedRoomDirect(User owner) {
        jdbc.update(
                "insert into rooms(name, owner_id, max_members) values (?, ?, ?)",
                "Room-" + Math.random(), owner.getId(), 5);
        return jdbc.queryForObject(
                "select id from rooms where owner_id = ? order by id desc limit 1",
                Long.class, owner.getId());
    }

    private void insertRevival(
            long roomId, long userId, Long giverUserId, String source, Instant occurredAt) {
        jdbc.update(
                "insert into revival_events(room_id, user_id, giver_user_id, source, "
                        + "source_subtype, points_spent, pool_after, eliminated_at, "
                        + "succeeded, occurred_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                roomId,
                userId,
                giverUserId,
                source,
                source.equals("FRIEND_GIFT") ? "PUSH_INITIATED" : null,
                (short) (source.equals("FREE_TICKET") ? 0
                        : source.equals("PERSONAL_POINTS") ? 3 : 5),
                source.equals("FREE_TICKET") ? 0
                        : source.equals("PERSONAL_POINTS") ? 3 : 5,
                Timestamp.from(occurredAt.minusSeconds(60)),
                true,
                Timestamp.from(occurredAt));
    }

    private static Authentication authFor(User u) {
        UserPrincipal principal = new UserPrincipal(u.getId(), u.getEmail());
        return new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }
}
