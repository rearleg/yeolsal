package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.notification.NotificationKind;
import com.yeosal.api.notification.NotificationLogRepository;
import com.yeosal.api.revival.LedgerReason;
import com.yeosal.api.revival.PersonalPointsLedgerRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration test for the Story 1.2 evaluator. Opt-in via
 * {@code -Dyeosal.boot-smoke=true} (mirroring {@code RoomControllerIT}
 * and {@code ApplicationBootSmokeTest}) so the standard {@code ./gradlew
 * test} stays fast and Docker-free.
 *
 * <p>Scenario (Story 1.2 AC2–AC9 end-to-end):
 * <ul>
 *   <li><strong>alice</strong> (owner) — has a {@code daily_entries} row
 *       for {@code priorEntryDate} → compliant → +2 SURVIVAL ledger row.</li>
 *   <li><strong>bob</strong> — no entry, no prior freeze this month → uses
 *       freeze, no state change.</li>
 *   <li><strong>charlie</strong> — no entry, freeze pre-consumed, ACTIVE →
 *       transitions to YELLOW.</li>
 *   <li><strong>diana</strong> — no entry, freeze pre-consumed, already
 *       YELLOW within 7d rolling window, out of grace → transitions to RED,
 *       queues a {@code pending_realtime_broadcasts} row with
 *       {@code scheduled_at = eliminated_at + 24h}.</li>
 * </ul>
 *
 * <p>Then re-runs {@code runEvaluation(date)} and asserts zero additional
 * writes (AC8 idempotency).
 *
 * <p>Project-context rule: H2 is forbidden. Always use Testcontainers
 * Postgres so jsonb and partial unique indexes behave correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class SurvivalStateEvaluatorIT {

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
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private DailyEntryRepository dailyEntries;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private SurvivalStateEvaluatorJob evaluatorJob;
    @Autowired private NotificationLogRepository notificationLogs;
    @Autowired private PersonalPointsLedgerRepository personalLedger;
    @Autowired private PendingRealtimeBroadcastRepository pendingBroadcasts;
    @Autowired private StreakFreezeRepository streakFreezes;
    @Autowired private RoomRuleVersionRepository ruleVersions;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("evaluator end-to-end: compliant + frozen + YELLOW + RED, re-run idempotent")
    void evaluator_endToEnd_writesExpectedRowsAndIsIdempotent() {
        LocalDate priorEntryDate = LocalDate.of(2026, 5, 11);
        String monthKey = "2026-05";

        // ----- seed -----
        User alice = users.save(new User("alice-evit@example.com", "Alice", "h", AuthProvider.EMAIL));
        User bob = users.save(new User("bob-evit@example.com", "Bob", "h", AuthProvider.EMAIL));
        User charlie = users.save(new User("charlie-evit@example.com", "Charlie", "h", AuthProvider.EMAIL));
        User diana = users.save(new User("diana-evit@example.com", "Diana", "h", AuthProvider.EMAIL));

        Room room = rooms.save(new Room("survival-it room", alice));

        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMember(room, alice, RoomRole.OWNER, joinedAt);
        seedMember(room, bob, RoomRole.MEMBER, joinedAt);
        seedMember(room, charlie, RoomRole.MEMBER, joinedAt);
        seedMember(room, diana, RoomRole.MEMBER, joinedAt);

        // V11 backfills rule_versions only for rooms existing at install — seed
        // a row for this freshly-created room (KST month-keyed).
        ObjectNode rulePayload = JsonNodeFactory.instance.objectNode();
        rulePayload.put("preset", "DAILY_UPDATE");
        rulePayload.put("weekendInclude", true);
        ruleVersions.save(new RoomRuleVersion(
                room.getId(), monthKey, rulePayload, alice.getId()));

        // survival_state rows minted by service so grace_ends_at = joinedAt + 14d
        // (which has passed for all four — eligible for state-machine transitions).
        survivalStateService.initializeOnJoin(room, alice, joinedAt);
        survivalStateService.initializeOnJoin(room, bob, joinedAt);
        survivalStateService.initializeOnJoin(room, charlie, joinedAt);
        survivalStateService.initializeOnJoin(room, diana, joinedAt);

        // alice: compliant — daily entry exists for prior date.
        dailyEntries.save(new DailyEntry(alice, priorEntryDate, "오늘의 목표"));

        // charlie: freeze pre-consumed this month → ACTIVE→YELLOW on miss.
        streakFreezes.save(new StreakFreeze(
                charlie, room, priorEntryDate.minusDays(5), monthKey));

        // diana: freeze pre-consumed + already YELLOW within rolling 7d window,
        // out of grace → YELLOW→RED on this miss.
        streakFreezes.save(new StreakFreeze(
                diana, room, priorEntryDate.minusDays(5), monthKey));
        forceSurvivalStateInDb(diana.getId(), room.getId(),
                SurvivalStatus.YELLOW,
                Instant.now().minus(Duration.ofDays(3)),  // within 7d window
                Instant.now().minus(Duration.ofDays(1))); // out of grace

        // ----- act -----
        SurvivalStateEvaluatorJob.Summary first = evaluatorJob.runEvaluation(priorEntryDate);

        // ----- assert (a) survival_state statuses -----
        assertStatus(alice, room, SurvivalStatus.ACTIVE);
        assertStatus(bob, room, SurvivalStatus.ACTIVE);
        assertStatus(charlie, room, SurvivalStatus.YELLOW);
        assertStatus(diana, room, SurvivalStatus.RED);

        SurvivalState dianaState =
                survivalStates.findByRoomIdAndUserId(room.getId(), diana.getId()).orElseThrow();
        assertThat(dianaState.getEliminatedAt()).isNotNull();
        assertThat(dianaState.getBroadVisibilityAt())
                .isEqualTo(dianaState.getEliminatedAt().plus(Duration.ofHours(24)));

        // ----- assert (b) notification_log rows for all four users -----
        assertThat(notificationLogs.existsByUserAndKindAndKey(
                alice, NotificationKind.SURVIVAL_STATE, priorEntryDate + ":" + room.getId() + ":" + alice.getId()))
                .isTrue();
        assertThat(notificationLogs.existsByUserAndKindAndKey(
                bob, NotificationKind.SURVIVAL_STATE, priorEntryDate + ":" + room.getId() + ":" + bob.getId()))
                .isTrue();
        assertThat(notificationLogs.existsByUserAndKindAndKey(
                charlie, NotificationKind.SURVIVAL_STATE, priorEntryDate + ":" + room.getId() + ":" + charlie.getId()))
                .isTrue();
        assertThat(notificationLogs.existsByUserAndKindAndKey(
                diana, NotificationKind.SURVIVAL_STATE, priorEntryDate + ":" + room.getId() + ":" + diana.getId()))
                .isTrue();

        // alice has the +2 SURVIVAL ledger row.
        assertThat(personalLedger.countByUserIdAndRoomIdAndReason(
                alice.getId(), room.getId(), LedgerReason.SURVIVAL))
                .isEqualTo(1L);
        // bob/diana did NOT get survival points.
        assertThat(personalLedger.countByUserIdAndRoomIdAndReason(
                bob.getId(), room.getId(), LedgerReason.SURVIVAL)).isZero();
        assertThat(personalLedger.countByUserIdAndRoomIdAndReason(
                diana.getId(), room.getId(), LedgerReason.SURVIVAL)).isZero();

        // bob's freeze write succeeded.
        assertThat(streakFreezes.existsByUserIdAndMonth(bob.getId(), monthKey)).isTrue();

        // ----- assert (d) pending_realtime_broadcasts row for diana's RED -----
        // AC7 / Codex review #5: the dispatcher MUST schedule the broad
        // fan-out exactly 24h after elimination — the load-bearing check that
        // privacy is not leaked at t=0.
        java.util.List<PendingRealtimeBroadcast> pending = pendingBroadcasts.findAll();
        assertThat(pending).as("exactly one pending broadcast — diana's RED only").hasSize(1);
        PendingRealtimeBroadcast dianaBroadcast = pending.get(0);
        assertThat(dianaBroadcast.getEmittedAt()).as("not yet emitted").isNull();
        assertThat(dianaBroadcast.getScheduledAt())
                .as("scheduled_at = eliminated_at + 24h (AC7/AC9)")
                .isEqualTo(dianaState.getEliminatedAt().plus(Duration.ofHours(24)));
        JsonNode dianaBroadcastPayload = dianaBroadcast.getPayload();
        assertThat(dianaBroadcastPayload.path("eventKind").asText()).isEqualTo("SURVIVAL_STATE_CHANGE");
        assertThat(dianaBroadcastPayload.path("roomId").asLong()).isEqualTo(room.getId());
        assertThat(dianaBroadcastPayload.path("userId").asLong()).isEqualTo(diana.getId());
        assertThat(dianaBroadcastPayload.path("toStatus").asText()).isEqualTo("RED");
        // The JSONB payload preserves the in-memory Instant precision (nanos),
        // while survival_state.eliminated_at is persisted to Postgres timestamptz
        // (microsecond precision). Both must represent the same instant once
        // truncated to the column's actual storage precision.
        Instant payloadEliminatedAt =
                Instant.parse(dianaBroadcastPayload.path("eliminatedAt").asText());
        assertThat(payloadEliminatedAt.truncatedTo(ChronoUnit.MICROS))
                .as("AC7 RED payload must carry explicit eliminatedAt field (Postgres timestamptz micros precision)")
                .isEqualTo(dianaState.getEliminatedAt().truncatedTo(ChronoUnit.MICROS));

        // first run summary sanity.
        assertThat(first.totalCompliant()).isEqualTo(1);
        assertThat(first.totalFrozen()).isEqualTo(1);
        assertThat(first.totalToYellow()).isEqualTo(1);
        assertThat(first.totalToRed()).isEqualTo(1);

        // ----- assert (c) idempotent re-run -----
        long ledgerCountBefore = personalLedger.count();
        long notificationCountBefore = notificationLogs.count();
        long pendingBroadcastsCountBefore = pendingBroadcasts.count();

        SurvivalStateEvaluatorJob.Summary second = evaluatorJob.runEvaluation(priorEntryDate);

        assertThat(second.totalCompliant()).isZero();
        assertThat(second.totalToYellow()).isZero();
        assertThat(second.totalToRed()).isZero();
        assertThat(personalLedger.count()).isEqualTo(ledgerCountBefore);
        assertThat(notificationLogs.count()).isEqualTo(notificationCountBefore);
        assertThat(pendingBroadcasts.count()).isEqualTo(pendingBroadcastsCountBefore);
    }

    // ----- helpers -----

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private void assertStatus(User user, Room room, SurvivalStatus expected) {
        SurvivalState state = survivalStates
                .findByRoomIdAndUserId(room.getId(), user.getId())
                .orElseThrow(() -> new AssertionError(
                        "survival_state missing for " + user.getEmail()));
        assertThat(state.getStatus())
                .as("survival_state.status for " + user.getEmail())
                .isEqualTo(expected);
    }

    /**
     * Inject a pre-existing YELLOW survival state for the rolling-window /
     * out-of-grace RED scenario. Package-private setters on
     * {@link SurvivalState} are accessible from this same package, but we
     * also need to overwrite {@code grace_ends_at} (set by
     * {@code initializeOnJoin}) — done via reflection inside one short
     * transaction.
     */
    private void forceSurvivalStateInDb(
            long userId,
            long roomId,
            SurvivalStatus status,
            Instant lastStateChangeAt,
            Instant graceEndsAt) {
        tx.executeWithoutResult(t -> {
            SurvivalState state = survivalStates
                    .findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow();
            state.setStatus(status);
            state.setLastStateChangeAt(lastStateChangeAt);
            try {
                Field f = SurvivalState.class.getDeclaredField("graceEndsAt");
                f.setAccessible(true);
                f.set(state, graceEndsAt);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            survivalStates.save(state);
        });
    }
}
