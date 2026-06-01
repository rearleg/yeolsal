package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 3.4 AC8 / BE-7 — Testcontainers IT for
 * {@link RevivalEventRepository#findReceivedRevivalsByRoom}. Four cases:
 * <ol>
 *   <li>Empty → empty list.</li>
 *   <li>One FREE_TICKET + one PERSONAL_POINTS + one FRIEND_GIFT → all 3
 *       returned, DESC ordered.</li>
 *   <li>Row where caller is {@code giver_user_id} (not {@code user_id})
 *       → NOT returned (AC9 privacy invariant from Story 3.2).</li>
 *   <li>{@code succeeded = false} row → NOT returned (defensive — Story
 *       3.1 / 3.2 services never write such rows but defence-in-depth).</li>
 * </ol>
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RevivalEventRepositoryReceivedTest {

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

    @Autowired private RevivalEventRepository revivalEvents;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

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
    @DisplayName("AC8-1 — empty → empty list")
    void empty_returnsEmpty() {
        User me = seedUser("me");
        long roomId = seedRoomDirect(me);

        List<RevivalEvent> rows =
                revivalEvents.findReceivedRevivalsByRoom(me.getId(), roomId);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("AC8-2 — FREE_TICKET + PERSONAL_POINTS + FRIEND_GIFT → all 3 DESC")
    void threeSources_allReturnedDesc() {
        User me = seedUser("me");
        User donor = seedUser("donor");
        long roomId = seedRoomDirect(me);

        insertRevival(roomId, me.getId(), null, "FREE_TICKET", T0, true);
        insertRevival(roomId, me.getId(), null, "PERSONAL_POINTS", T1, true);
        insertRevival(roomId, me.getId(), donor.getId(), "FRIEND_GIFT", T2, true);

        List<RevivalEvent> rows =
                revivalEvents.findReceivedRevivalsByRoom(me.getId(), roomId);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(RevivalEvent::getSource)
                .containsExactly(RevivalSource.FRIEND_GIFT,
                        RevivalSource.PERSONAL_POINTS, RevivalSource.FREE_TICKET);
        // Donor id surfaced only on the FRIEND_GIFT row.
        assertThat(rows.get(0).getGiverUserId()).isEqualTo(donor.getId());
        assertThat(rows.get(1).getGiverUserId()).isNull();
        assertThat(rows.get(2).getGiverUserId()).isNull();
    }

    @Test
    @DisplayName("AC8-3 — row where caller is giver_user_id is NOT returned (privacy)")
    void callerIsDonor_excluded() {
        User me = seedUser("me");
        User receiver = seedUser("receiver");
        long roomId = seedRoomDirect(me);

        // Me as the DONOR — this row belongs to the receiver's history, not mine.
        insertRevival(roomId, receiver.getId(), me.getId(), "FRIEND_GIFT", T0, true);

        List<RevivalEvent> mine =
                revivalEvents.findReceivedRevivalsByRoom(me.getId(), roomId);

        assertThat(mine).isEmpty();
    }

    @Test
    @DisplayName("AC8-4 — succeeded=false rows are NOT returned (defence-in-depth)")
    void succeededFalse_excluded() {
        User me = seedUser("me");
        long roomId = seedRoomDirect(me);

        // Hypothetical failed-revival row (current services never write these).
        insertRevival(roomId, me.getId(), null, "PERSONAL_POINTS", T0, false);

        List<RevivalEvent> rows =
                revivalEvents.findReceivedRevivalsByRoom(me.getId(), roomId);

        assertThat(rows).isEmpty();
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

    /**
     * Inserts a {@code revival_events} row via raw SQL — bypasses the
     * service's {@code ON CONFLICT} idempotency path because these tests
     * verify the read-side filter, not the write-side defence.
     *
     * <p>{@code succeeded=false} rows cannot land via the service path
     * (Story 3.1 / 3.2 only write {@code true}). The partial unique index
     * ({@code WHERE succeeded = true}) does NOT block a {@code false} row,
     * so this case exercises the {@code WHERE succeeded = true} clause
     * inside the query itself.
     */
    private void insertRevival(
            long roomId, long userId, Long giverUserId, String source,
            Instant occurredAt, boolean succeeded) {
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
                succeeded,
                Timestamp.from(occurredAt));
    }
}
