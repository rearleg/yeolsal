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
 * Story 3.3 BE-8 / AC7 — Testcontainers IT for
 * {@link FriendGiftTargetQuery}. Mirrors the
 * {@code @SpringBootTest + @EnabledIfSystemProperty} precedent set by
 * {@link MeSurvivalFreeTicketIT}.
 *
 * <p>Eight cases enumerated at story file AC7:
 * <ol>
 *   <li>Two rooms, one with eligible friend (RED + ACCEPTED)</li>
 *   <li>One room with TWO eligible friends (RED + SPECTATOR)</li>
 *   <li>Friendship PENDING → excluded</li>
 *   <li>Friend status ACTIVE → excluded</li>
 *   <li>Caller SPECTATOR → empty list (giver-side gate)</li>
 *   <li>Caller is NOT a room member → excluded</li>
 *   <li>Self-target → never returned</li>
 *   <li>Cross-direction friendship row (addressee = caller) → found</li>
 * </ol>
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class FriendGiftTargetQueryTest {

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

    @Autowired private FriendGiftTargetQuery query;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private SurvivalStateService survivalStateService;
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
    @DisplayName("AC7-1 — two rooms, one with one eligible RED friend → list of 1, eligibleCount=1")
    void twoRooms_oneEligible_returnsSingleEntry() {
        User giver = seedUser("g1");
        User friend = seedUser("f1");
        User stranger = seedUser("s1");
        Room rA = seedRoom("Room A", giver, giver, friend);
        seedRoom("Room B", giver, giver, stranger);
        flipStatus(rA.getId(), friend.getId(), "RED", ELIMINATED_AT);
        // stranger left status ACTIVE in Room B → no eligible target there
        seedFriendship(giver, friend, "ACCEPTED");

        List<FriendGiftTargetQuery.EligibleFriendRow> rows =
                query.findEligibleTargets(giver.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).roomId()).isEqualTo(rA.getId());
        assertThat(rows.get(0).targetUserId()).isEqualTo(friend.getId());
        assertThat(rows.get(0).targetStatus()).isEqualTo("RED");
    }

    @Test
    @DisplayName("AC7-2 — one room, RED + SPECTATOR friends → two rows for the same room")
    void oneRoom_twoEligibleFriends_returnsTwoRows() {
        User giver = seedUser("g2");
        User redFriend = seedUser("red");
        User spectatorFriend = seedUser("spec");
        Room r = seedRoom("Room", giver, giver, redFriend, spectatorFriend);
        flipStatus(r.getId(), redFriend.getId(), "RED", ELIMINATED_AT);
        flipStatus(r.getId(), spectatorFriend.getId(), "SPECTATOR", ELIMINATED_AT.minusSeconds(60));
        seedFriendship(giver, redFriend, "ACCEPTED");
        seedFriendship(giver, spectatorFriend, "ACCEPTED");

        List<FriendGiftTargetQuery.EligibleFriendRow> rows =
                query.findEligibleTargets(giver.getId());

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(FriendGiftTargetQuery.EligibleFriendRow::targetStatus)
                .containsExactlyInAnyOrder("RED", "SPECTATOR");
    }

    @Test
    @DisplayName("AC7-3 — friendship PENDING → excluded")
    void friendshipPending_excluded() {
        User giver = seedUser("g3");
        User pendingFriend = seedUser("p3");
        Room r = seedRoom("Room", giver, giver, pendingFriend);
        flipStatus(r.getId(), pendingFriend.getId(), "RED", ELIMINATED_AT);
        seedFriendship(giver, pendingFriend, "PENDING");

        assertThat(query.findEligibleTargets(giver.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC7-4 — friend status ACTIVE → excluded")
    void friendActive_excluded() {
        User giver = seedUser("g4");
        User activeFriend = seedUser("a4");
        seedRoom("Room", giver, giver, activeFriend);
        // status left ACTIVE (the default after initializeOnJoin)
        seedFriendship(giver, activeFriend, "ACCEPTED");

        assertThat(query.findEligibleTargets(giver.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC7-5 — caller SPECTATOR → empty list (giver-side gate fires SQL-side)")
    void callerSpectator_returnsEmpty() {
        User giver = seedUser("g5");
        User friend = seedUser("f5");
        Room r = seedRoom("Room", giver, giver, friend);
        flipStatus(r.getId(), friend.getId(), "RED", ELIMINATED_AT);
        flipStatus(r.getId(), giver.getId(), "SPECTATOR", ELIMINATED_AT.minusSeconds(120));
        seedFriendship(giver, friend, "ACCEPTED");

        assertThat(query.findEligibleTargets(giver.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC7-6 — caller NOT a room member of the candidate's room → excluded")
    void callerNotMember_excluded() {
        User giver = seedUser("g6");
        User strangerOwner = seedUser("so6");
        User friend = seedUser("f6");
        // Room without giver as a member; friend is in it.
        Room r = seedRoom("Closed Room", strangerOwner, strangerOwner, friend);
        flipStatus(r.getId(), friend.getId(), "RED", ELIMINATED_AT);
        seedFriendship(giver, friend, "ACCEPTED");

        // Giver also needs a separate room to ensure their own survival_state
        // exists; otherwise the sst_giver join is naturally empty in this room
        // and the result is empty for the same reason — both shapes confirm
        // the room_members + sst_giver gates work.
        seedRoom("Empty Room", giver, giver);

        assertThat(query.findEligibleTargets(giver.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC7-7 — self-target never returned (sst.user_id <> giverUserId clause)")
    void selfTarget_neverReturned() {
        User giver = seedUser("g7");
        Room r = seedRoom("Room", giver, giver);
        // Force giver themselves to RED — self-target should still be filtered.
        flipStatus(r.getId(), giver.getId(), "RED", ELIMINATED_AT);
        // Self-friendship row impossible per the unique constraint, but the
        // SQL also defends with sst.user_id <> :giverUserId regardless.

        assertThat(query.findEligibleTargets(giver.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC7-8 — cross-direction friendship (addressee = giver) is found by the OR clause")
    void crossDirectionFriendship_found() {
        User giver = seedUser("g8");
        User friend = seedUser("f8");
        Room r = seedRoom("Room", giver, giver, friend);
        flipStatus(r.getId(), friend.getId(), "RED", ELIMINATED_AT);
        // Flip the direction: friend is the requester, giver is the addressee.
        seedFriendship(friend, giver, "ACCEPTED");

        List<FriendGiftTargetQuery.EligibleFriendRow> rows =
                query.findEligibleTargets(giver.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).targetUserId()).isEqualTo(friend.getId());
    }

    // ----- helpers -----

    private User seedUser(String tag) {
        return users.save(new User(
                tag + "-" + Instant.now().toEpochMilli() + "-" + Math.random() + "@example.com",
                tag, "h", AuthProvider.EMAIL));
    }

    /**
     * Creates a room owned by {@code owner} (always a member) plus zero or
     * more additional members. Initializes survival_state for everyone via
     * {@link SurvivalStateService#initializeOnJoin}.
     */
    private Room seedRoom(String name, User owner, User... members) {
        Room room = rooms.save(new Room(name, owner));
        for (User m : members) {
            RoomMember rm = new RoomMember(room, m,
                    m.getId().equals(owner.getId()) ? RoomRole.OWNER : RoomRole.MEMBER);
            rm.setJoinedAt(JOINED_AT);
            roomMembers.save(rm);
            survivalStateService.initializeOnJoin(room, m, JOINED_AT);
        }
        return room;
    }

    private void flipStatus(long roomId, long userId, String status, Instant eliminatedAt) {
        jdbc.update(
                "update survival_state set status = ?, eliminated_at = ?, "
                        + "last_state_change_at = now() "
                        + "where room_id = ? and user_id = ?",
                status, Timestamp.from(eliminatedAt), roomId, userId);
    }

    private void seedFriendship(User requester, User addressee, String status) {
        jdbc.update(
                "insert into friendships(requester_id, addressee_id, status) "
                        + "values (?, ?, ?)",
                requester.getId(), addressee.getId(), status);
    }
}
