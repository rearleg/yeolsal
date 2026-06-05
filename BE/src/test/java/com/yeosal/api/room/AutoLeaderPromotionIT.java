package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.revival.RevivalService;
import com.yeosal.api.revival.RevivalSource;
import com.yeosal.api.survival.RoomRuleVersion;
import com.yeosal.api.survival.RoomRuleVersionRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
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
 * Story 5.3 AC11 row 3 — full-stack end-to-end coverage for
 * {@link AutoLeaderPromotionListener}. Drives elimination via the real
 * {@link SurvivalStateService#evaluateRoom(long, java.time.LocalDate)}
 * publisher chain so the AFTER_COMMIT listener wiring is genuinely
 * exercised (NOT the unit-layer direct invocation).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Happy path — leader YELLOW→RED → longest-tenured ACTIVE member
 *       becomes owner_id + both roles flip + AUTO_ELIMINATION frame
 *       published.</li>
 *   <li>Dormant — leader RED with no ACTIVE peers → owner_id unchanged,
 *       no LeadershipChange emission.</li>
 *   <li>Revival no-reclaim — after auto-promotion, the original leader
 *       self-revives → ACTIVE event filters out → owner_id stays with
 *       the auto-promoted new leader.</li>
 * </ol>
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true} (mirrors Story 5.2's
 * {@link RoomMemberCapPromotionIT} / Story 1.2's
 * {@code SurvivalStateEvaluatorIT}).
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class AutoLeaderPromotionIT {

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
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private RoomRuleVersionRepository ruleVersions;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private RevivalService revivalService;
    @SpyBean private SimpMessagingTemplate messaging;

    @BeforeEach
    void cleanup() {
        reset(messaging);
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
    @DisplayName("happy path — leader YELLOW→RED elects longest-tenured ACTIVE + publishes AUTO_ELIMINATION")
    void leaderEliminated_longestTenuredActivePromoted() {
        User leader = saveUser("leader-it@example.com", "Leader");
        User oldest = saveUser("oldest-it@example.com", "Oldest");
        User middle = saveUser("middle-it@example.com", "Middle");
        User newest = saveUser("newest-it@example.com", "Newest");

        Room room = rooms.save(new Room("auto-leader IT room", leader));
        Instant baseJoin = Instant.parse("2026-04-01T00:00:00Z");
        seedMembership(room, leader, RoomRole.OWNER, baseJoin);
        // Non-chronological insert order so the order-by clause is load-bearing.
        seedMembership(room, newest, RoomRole.MEMBER, baseJoin.plus(Duration.ofDays(30)));
        seedMembership(room, oldest, RoomRole.MEMBER, baseJoin.plus(Duration.ofDays(10)));
        seedMembership(room, middle, RoomRole.MEMBER, baseJoin.plus(Duration.ofDays(20)));

        survivalStateService.initializeOnJoin(room, leader, baseJoin);
        survivalStateService.initializeOnJoin(room, oldest, baseJoin.plus(Duration.ofDays(10)));
        survivalStateService.initializeOnJoin(room, middle, baseJoin.plus(Duration.ofDays(20)));
        survivalStateService.initializeOnJoin(room, newest, baseJoin.plus(Duration.ofDays(30)));

        LocalDate priorEntryDate = LocalDate.of(2026, 5, 11);
        seedRulePayload(room.getId(), leader.getId(), "2026-05");

        // Force leader to YELLOW within rolling window and out of grace,
        // and pre-consume their monthly freeze so the YELLOW→RED branch
        // actually runs on the next miss.
        forceYellowOutOfGrace(room.getId(), leader.getId());
        preConsumeFreeze(leader.getId(), room.getId(), priorEntryDate.minusDays(5));

        survivalStateService.evaluateRoom(room.getId(), priorEntryDate);

        // ----- assert: owner_id flipped to the oldest non-leader ACTIVE -----
        // The afterCommit publish runs out-of-band; poll the row state.
        waitUntil(2_000, () -> rooms.findById(room.getId())
                .map(r -> r.getOwner().getId().equals(oldest.getId()))
                .orElse(false));
        Room reloaded = rooms.findById(room.getId()).orElseThrow();
        RoomMember oldestMembership = roomMembers
                .findByRoomAndUser(reloaded, oldest).orElseThrow();
        RoomMember leaderMembership = roomMembers
                .findByRoomAndUser(reloaded, leader).orElseThrow();
        assertThat(oldestMembership.getRole()).isEqualTo(RoomRole.OWNER);
        assertThat(leaderMembership.getRole()).isEqualTo(RoomRole.MEMBER);

        // ----- assert: AUTO_ELIMINATION frame emitted -----
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging, timeout(2_000).atLeastOnce())
                .convertAndSend(eq("/topic/rooms." + room.getId() + ".survival"),
                        payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isInstanceOf(LeadershipChangePayload.class);
        LeadershipChangePayload payload = (LeadershipChangePayload) payloadCaptor.getValue();
        assertThat(payload.roomId()).isEqualTo(room.getId());
        assertThat(payload.previousLeaderUserId()).isEqualTo(leader.getId());
        assertThat(payload.newLeaderUserId()).isEqualTo(oldest.getId());
        assertThat(payload.reason()).isEqualTo("AUTO_ELIMINATION");
    }

    @Test
    @DisplayName("dormant — leader RED with no ACTIVE peers → owner_id unchanged, no emission")
    void leaderEliminated_dormantRoom_noFlipNoEmission() {
        User leader = saveUser("leader-dormant@example.com", "Leader");
        User redMember = saveUser("red-member@example.com", "Red");

        Room room = rooms.save(new Room("dormant IT room", leader));
        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMembership(room, leader, RoomRole.OWNER, joinedAt);
        seedMembership(room, redMember, RoomRole.MEMBER, joinedAt.plus(Duration.ofDays(1)));

        survivalStateService.initializeOnJoin(room, leader, joinedAt);
        survivalStateService.initializeOnJoin(
                room, redMember, joinedAt.plus(Duration.ofDays(1)));

        LocalDate priorEntryDate = LocalDate.of(2026, 5, 11);
        seedRulePayload(room.getId(), leader.getId(), "2026-05");

        // Force the only non-leader member to RED so nobody is ACTIVE.
        forceStatus(room.getId(), redMember.getId(), "RED");
        forceYellowOutOfGrace(room.getId(), leader.getId());
        preConsumeFreeze(leader.getId(), room.getId(), priorEntryDate.minusDays(5));

        survivalStateService.evaluateRoom(room.getId(), priorEntryDate);

        // Allow time for any stray AFTER_COMMIT emission to land — the
        // assertion below is the load-bearing one.
        sleepQuietly(500);

        Room reloaded = rooms.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getOwner().getId()).isEqualTo(leader.getId());
        verify(messaging, never()).convertAndSend(
                eq("/topic/rooms." + room.getId() + ".survival"),
                any(LeadershipChangePayload.class));
    }

    @Test
    @DisplayName("revival no-reclaim — auto-promoted; original leader self-revives → owner stays with new leader")
    void revivedFormerLeaderDoesNotReclaim() {
        User leader = saveUser("leader-rev@example.com", "Leader");
        User newLeader = saveUser("new-leader-rev@example.com", "NewLeader");

        Room room = rooms.save(new Room("revival no-reclaim IT room", leader));
        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMembership(room, leader, RoomRole.OWNER, joinedAt);
        seedMembership(room, newLeader, RoomRole.MEMBER, joinedAt.plus(Duration.ofDays(1)));

        survivalStateService.initializeOnJoin(room, leader, joinedAt);
        survivalStateService.initializeOnJoin(
                room, newLeader, joinedAt.plus(Duration.ofDays(1)));

        LocalDate priorEntryDate = LocalDate.of(2026, 5, 11);
        seedRulePayload(room.getId(), leader.getId(), "2026-05");

        forceYellowOutOfGrace(room.getId(), leader.getId());
        preConsumeFreeze(leader.getId(), room.getId(), priorEntryDate.minusDays(5));
        // Seed the lifetime free-ticket-used flag + ample PERSONAL_POINTS
        // ledger so reviveSelf(PERSONAL_POINTS) prerequisite checks pass.
        jdbc.update("UPDATE users SET free_revival_ticket_used = true WHERE id = ?",
                leader.getId());
        jdbc.update(
                // V11 schema names the column `delta` (smallint), not
                // `points_delta`. The prior literal was a latent fixture
                // bug that PR-CI masked under cascade-fail noise from
                // earlier opt-in ITs.
                "INSERT INTO personal_points_ledger"
                        + " (user_id, room_id, delta, reason, occurred_at)"
                        + " VALUES (?, ?, 100, 'SURVIVAL', now())",
                leader.getId(), room.getId());
        // Also mint a room_point_pool row (RoomService.create normally mints
        // it; the bare `new Room(...)` save bypasses that hook).
        jdbc.update(
                "INSERT INTO room_point_pool (room_id, total) VALUES (?, 0)"
                        + " ON CONFLICT (room_id) DO NOTHING",
                room.getId());

        survivalStateService.evaluateRoom(room.getId(), priorEntryDate);

        // Wait for the auto-promotion afterCommit to land.
        waitUntil(2_000, () -> rooms.findById(room.getId())
                .map(r -> r.getOwner().getId().equals(newLeader.getId()))
                .orElse(false));
        // Reset spy so the assertion below covers ONLY the revival event.
        reset(messaging);

        // The previous leader self-revives → emits ACTIVE event → AC2
        // filter rejects → no listener-driven owner_id mutation.
        revivalService.reviveSelf(
                room.getId(), leader.getId(), RevivalSource.PERSONAL_POINTS);

        sleepQuietly(500);

        Room reloaded = rooms.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getOwner().getId()).isEqualTo(newLeader.getId());
        verify(messaging, never()).convertAndSend(
                eq("/topic/rooms." + room.getId() + ".survival"),
                any(LeadershipChangePayload.class));

        SurvivalState leaderState = survivalStates
                .findByRoomIdAndUserId(room.getId(), leader.getId()).orElseThrow();
        assertThat(leaderState.getStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        RoomMember leaderMembership = roomMembers
                .findByRoomAndUser(reloaded, leader).orElseThrow();
        assertThat(leaderMembership.getRole()).isEqualTo(RoomRole.MEMBER);
    }

    // ----- helpers -----

    private User saveUser(String email, String nickname) {
        return users.save(new User(email, nickname, "h", AuthProvider.EMAIL));
    }

    private void seedMembership(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private void seedRulePayload(long roomId, long ownerId, String monthKey) {
        ObjectNode rulePayload = JsonNodeFactory.instance.objectNode();
        rulePayload.put("preset", "DAILY_UPDATE");
        rulePayload.put("weekendInclude", true);
        ruleVersions.save(new RoomRuleVersion(roomId, monthKey, rulePayload, ownerId));
    }

    private void forceYellowOutOfGrace(long roomId, long userId) {
        // Place the YELLOW transition within the 7-day rolling window and
        // grace_ends_at in the past so the YELLOW→RED branch is eligible.
        jdbc.update(
                "UPDATE survival_state "
                        + "SET status = 'YELLOW', last_state_change_at = now() - interval '3 days',"
                        + " grace_ends_at = now() - interval '1 day' "
                        + "WHERE room_id = ? AND user_id = ?",
                roomId, userId);
    }

    private void forceStatus(long roomId, long userId, String status) {
        jdbc.update(
                "UPDATE survival_state SET status = ?, last_state_change_at = now() "
                        + "WHERE room_id = ? AND user_id = ?",
                status, roomId, userId);
    }

    private void preConsumeFreeze(long userId, long roomId, LocalDate entryDate) {
        // Pre-consume the user's monthly streak freeze so a subsequent miss
        // falls through to the survival state machine instead of freezing.
        // V11 columns: applied_date (not entry_date); month (not month_key).
        String monthKey = entryDate.toString().substring(0, 7);
        jdbc.update(
                "INSERT INTO streak_freezes (user_id, room_id, applied_date, month, created_at)"
                        + " VALUES (?, ?, ?, ?, now())"
                        + " ON CONFLICT (user_id, month) DO NOTHING",
                userId, roomId, java.sql.Date.valueOf(entryDate), monthKey);
    }

    private static void waitUntil(long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(50);
        }
        throw new AssertionError(
                "condition not satisfied within " + timeoutMillis + "ms");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
