package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Story 3.3 BE-8 / AC7 — end-to-end Testcontainers IT covering the
 * {@code revival_events.source_subtype} write invariant.
 *
 * <p>Two cases:
 * <ol>
 *   <li>{@code reviveFriend(...sourceSubtype = WALLET_INITIATED)} →
 *       persisted row has {@code source_subtype = 'WALLET_INITIATED'}.</li>
 *   <li>{@code reviveFriend(...sourceSubtype = null)} → persisted row
 *       has {@code source_subtype = 'PUSH_INITIATED'} (Story 3.2
 *       backward compat — no field on the wire).</li>
 * </ol>
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class FriendGiftWalletInitiatedIT {

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

    private static final Instant ELIMINATED_AT = Instant.parse("2026-05-18T03:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-04-01T00:00:00Z");

    @Autowired private RevivalService revivalService;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private RoomPointPoolRepository roomPointPool;
    @Autowired private PersonalPointsLedgerRepository personalLedger;
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
    @DisplayName("AC3 — sourceSubtype=WALLET_INITIATED → revival_events.source_subtype='WALLET_INITIATED'")
    void walletInitiated_persistsString() {
        Fixture f = seed();
        revivalService.reviveFriend(
                f.room.getId(), f.giver, f.receiver.getId(),
                RevivalSourceSubtype.WALLET_INITIATED);

        String persisted = jdbc.queryForObject(
                "select source_subtype from revival_events "
                        + "where room_id = ? and user_id = ?",
                String.class, f.room.getId(), f.receiver.getId());
        assertThat(persisted).isEqualTo("WALLET_INITIATED");
    }

    @Test
    @DisplayName("AC3 — sourceSubtype=null → revival_events.source_subtype='PUSH_INITIATED' (back-compat)")
    void nullSubtype_defaultsToPush() {
        Fixture f = seed();
        revivalService.reviveFriend(
                f.room.getId(), f.giver, f.receiver.getId(), null);

        String persisted = jdbc.queryForObject(
                "select source_subtype from revival_events "
                        + "where room_id = ? and user_id = ?",
                String.class, f.room.getId(), f.receiver.getId());
        assertThat(persisted).isEqualTo("PUSH_INITIATED");
    }

    // ----- helpers -----

    private record Fixture(User giver, User receiver, Room room) {}

    private Fixture seed() {
        User giver = users.save(new User(
                "g-" + Instant.now().toEpochMilli() + "@example.com",
                "G", "h", AuthProvider.EMAIL));
        User receiver = users.save(new User(
                "r-" + Instant.now().toEpochMilli() + "@example.com",
                "R", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("Room", giver));

        RoomMember rmG = new RoomMember(room, giver, RoomRole.OWNER);
        rmG.setJoinedAt(JOINED_AT);
        roomMembers.save(rmG);
        RoomMember rmR = new RoomMember(room, receiver, RoomRole.MEMBER);
        rmR.setJoinedAt(JOINED_AT);
        roomMembers.save(rmR);

        survivalStateService.initializeOnJoin(room, giver, JOINED_AT);
        survivalStateService.initializeOnJoin(room, receiver, JOINED_AT);
        // Receiver → RED; giver stays ACTIVE.
        jdbc.update(
                "update survival_state set status = 'RED', eliminated_at = ?, "
                        + "last_state_change_at = now() where room_id = ? and user_id = ?",
                Timestamp.from(ELIMINATED_AT), room.getId(), receiver.getId());

        // Friendship row giver→receiver, ACCEPTED.
        jdbc.update(
                "insert into friendships(requester_id, addressee_id, status) "
                        + "values (?, ?, 'ACCEPTED')",
                giver.getId(), receiver.getId());

        // Pool row + giver balance >= 5.
        if (roomPointPool.findTotalByRoomId(room.getId()) == null) {
            roomPointPool.save(new RoomPointPool(room.getId(), 0));
        }
        tx.executeWithoutResult(t -> personalLedger.save(new PersonalPointsLedger(
                giver.getId(), room.getId(), (short) 5,
                LedgerReason.ADJUSTMENT, Instant.now(), null)));

        return new Fixture(giver, receiver, room);
    }
}
