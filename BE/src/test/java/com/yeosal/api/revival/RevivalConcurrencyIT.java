package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Story 3.1 AC5 + AC11 concurrency contract. Two parallel
 * {@code reviveSelf} calls compete inside the advisory lock + partial
 * unique index — exactly one wins, the other surfaces
 * {@link AlreadyRevivedException}.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}, mirroring the other
 * Testcontainers ITs (project-context rule: H2 forbidden).
 *
 * <p>Three race variants:
 * <ol>
 *   <li>FREE_TICKET × 2 — winner consumes the lifetime-one flag and
 *       bumps the pool by 5; loser surfaces an exception (advisory lock
 *       primary defence or {@link FreeTicketAlreadyUsedException} if
 *       the loser somehow races past the lock — both are acceptable per
 *       the story contract; exactly one writes the revival row).</li>
 *   <li>PERSONAL_POINTS × 2 — balance starts at 6; exactly one
 *       revival succeeds, ledger gains one {@code -3} row, pool bumped
 *       by 3, residual balance = 3.</li>
 *   <li>FREE_TICKET vs PERSONAL_POINTS — different sources, same
 *       elimination; exactly one row in {@code revival_events} survives
 *       the partial unique index.</li>
 * </ol>
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RevivalConcurrencyIT {

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

    @Autowired private RevivalService revivalService;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private RevivalEventRepository revivalEvents;
    @Autowired private RoomPointPoolRepository roomPointPool;
    @Autowired private PersonalPointsLedgerRepository personalLedger;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

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
    @DisplayName("FREE_TICKET × 2 race — exactly one revival succeeds; pool=5; ticket consumed; status=ACTIVE")
    void freeTicket_x2_raceCollapsesToExactlyOne() throws Exception {
        Fixture f = seed();
        Instant eliminatedAt = forceRed(f.user().getId(), f.room().getId());

        Outcomes outcomes = race(
                () -> revivalService.reviveSelf(f.room().getId(), f.user().getId(), RevivalSource.FREE_TICKET),
                () -> revivalService.reviveSelf(f.room().getId(), f.user().getId(), RevivalSource.FREE_TICKET));

        assertThat(outcomes.successes()).isEqualTo(1);
        assertThat(outcomes.failures()).isEqualTo(1);
        // AC5 / AC11 — loser MUST receive AlreadyRevivedException (advisory
        // lock primary defence) OR FreeTicketAlreadyUsedException (lifetime
        // flag winner — also acceptable per the AC5 contract since both
        // surface as 4xx after the partial unique index collapses).
        assertThat(outcomes.errors())
                .singleElement()
                .satisfies(ex -> assertThat(ex)
                        .isInstanceOfAny(
                                AlreadyRevivedException.class,
                                FreeTicketAlreadyUsedException.class));

        long roomId = f.room().getId();
        long userId = f.user().getId();
        assertThat(revivalEvents.findByRoomIdAndUserIdAndEliminatedAt(roomId, userId, eliminatedAt))
                .isPresent();
        assertThat(jdbc.queryForObject(
                "select count(*) from revival_events where room_id = ? and user_id = ?",
                Long.class, roomId, userId))
                .isEqualTo(1L);
        assertThat(roomPointPool.findTotalByRoomId(roomId))
                .isEqualTo((int) RevivalService.FREE_TICKET_POOL_DELTA);
        User refreshed = users.findById(userId).orElseThrow();
        assertThat(refreshed.isFreeRevivalTicketUsed()).isTrue();
        SurvivalState after = survivalStates.findByRoomIdAndUserId(roomId, userId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(after.getEliminatedAt()).isNull();
        assertThat(after.getBroadVisibilityAt()).isNull();
    }

    @Test
    @DisplayName("PERSONAL_POINTS × 2 race — exactly one succeeds; ledger debited once; pool=+3; balance=3")
    void personalPoints_x2_raceCollapsesToExactlyOne() throws Exception {
        Fixture f = seed();
        long roomId = f.room().getId();
        long userId = f.user().getId();
        forceRed(userId, roomId);
        seedLedger(userId, roomId, (short) 6);
        users.markFreeTicketUsed(userId);

        Outcomes outcomes = race(
                () -> revivalService.reviveSelf(roomId, userId, RevivalSource.PERSONAL_POINTS),
                () -> revivalService.reviveSelf(roomId, userId, RevivalSource.PERSONAL_POINTS));

        assertThat(outcomes.successes()).isEqualTo(1);
        assertThat(outcomes.failures()).isEqualTo(1);
        // AC5 — loser sees AlreadyRevivedException via the advisory-lock
        // primary defence (status flipped ACTIVE before the loser re-reads).
        assertThat(outcomes.errors())
                .singleElement()
                .isInstanceOf(AlreadyRevivedException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from revival_events where room_id = ? and user_id = ?",
                Long.class, roomId, userId))
                .isEqualTo(1L);
        Integer balance = personalLedger.sumDeltaByUserIdAndRoomId(userId, roomId);
        assertThat(balance).isEqualTo(3);
        Long debits = jdbc.queryForObject(
                "select count(*) from personal_points_ledger "
                        + "where user_id = ? and room_id = ? and reason = 'REVIVAL_SPEND'",
                Long.class, userId, roomId);
        assertThat(debits).isEqualTo(1L);
        assertThat(roomPointPool.findTotalByRoomId(roomId))
                .isEqualTo((int) RevivalService.PERSONAL_POINTS_POOL_DELTA);
        SurvivalState after = survivalStates.findByRoomIdAndUserId(roomId, userId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SurvivalStatus.ACTIVE);
    }

    @Test
    @DisplayName("FREE_TICKET vs PERSONAL_POINTS race — exactly one wins the partial unique index")
    void freeTicket_vs_personalPoints_race() throws Exception {
        Fixture f = seed();
        long roomId = f.room().getId();
        long userId = f.user().getId();
        forceRed(userId, roomId);
        seedLedger(userId, roomId, (short) 6);

        Outcomes outcomes = race(
                () -> revivalService.reviveSelf(roomId, userId, RevivalSource.FREE_TICKET),
                () -> revivalService.reviveSelf(roomId, userId, RevivalSource.PERSONAL_POINTS));

        assertThat(outcomes.successes()).isEqualTo(1);
        assertThat(outcomes.failures()).isEqualTo(1);
        // Two valid loser shapes:
        //   - FREE_TICKET wins lock first → PERSONAL_POINTS gets the lock
        //     next, refresh() sees status=ACTIVE → AlreadyRevivedException.
        //   - PERSONAL_POINTS wins lock first → AC3 lifetime guard fires
        //     (user.freeRevivalTicketUsed == false) → BadRequestException.
        //     FREE_TICKET then wins after the lock releases.
        // Either way exactly one row lands in revival_events.
        assertThat(outcomes.errors())
                .singleElement()
                .satisfies(ex -> assertThat(ex)
                        .isInstanceOfAny(
                                AlreadyRevivedException.class,
                                com.yeosal.api.common.BadRequestException.class));
        assertThat(jdbc.queryForObject(
                "select count(*) from revival_events where room_id = ? and user_id = ?",
                Long.class, roomId, userId))
                .isEqualTo(1L);
        SurvivalState after = survivalStates.findByRoomIdAndUserId(roomId, userId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SurvivalStatus.ACTIVE);
    }

    // ----- helpers -----

    private record Fixture(User user, Room room) {}

    private Fixture seed() {
        User owner = users.save(new User(
                "owner-revival@example.com", "Owner", "h", AuthProvider.EMAIL));
        User user = users.save(new User(
                "user-revival@example.com", "User", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("revival-it room", owner));
        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMember(room, owner, RoomRole.OWNER, joinedAt);
        seedMember(room, user, RoomRole.MEMBER, joinedAt);
        survivalStateService.initializeOnJoin(room, owner, joinedAt);
        survivalStateService.initializeOnJoin(room, user, joinedAt);
        // V11 step 15 backfill is a one-shot at migration time; for fresh
        // rooms minted in this test, seed a pool row explicitly.
        if (roomPointPool.findTotalByRoomId(room.getId()) == null) {
            roomPointPool.save(new RoomPointPool(room.getId(), 0));
        }
        return new Fixture(user, room);
    }

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private Instant forceRed(long userId, long roomId) {
        Instant eliminatedAt = Instant.now().minus(Duration.ofHours(12));
        tx.executeWithoutResult(t -> {
            SurvivalState state = survivalStates
                    .findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow();
            // SurvivalState's mutators are package-private to survival/.
            // The IT lives in revival/, so reflection is the only way to
            // seed the RED state (mirrors SurvivalStateServiceMeAcrossRoomsTest
            // helper precedent).
            setField(state, "status", SurvivalStatus.RED);
            setField(state, "lastStateChangeAt", eliminatedAt);
            setField(state, "eliminatedAt", eliminatedAt);
            setField(state, "broadVisibilityAt", eliminatedAt.plus(Duration.ofHours(24)));
            survivalStates.save(state);
        });
        return eliminatedAt;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void seedLedger(long userId, long roomId, short totalDelta) {
        tx.executeWithoutResult(t -> personalLedger.save(new PersonalPointsLedger(
                userId, roomId, totalDelta, LedgerReason.SURVIVAL, Instant.now())));
    }

    /**
     * Runs two suppliers in parallel from a starting gate so both threads
     * step into the service nearly simultaneously, mimicking the
     * race-from-two-tabs scenario the partial unique index defends
     * against. Captures any exception thrown by the loser so tests can
     * prove the AC5 contract — loser MUST receive
     * {@link AlreadyRevivedException}, not a generic failure.
     */
    private static Outcomes race(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger(0);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> a = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    first.get();
                    successes.incrementAndGet();
                } catch (Exception ex) {
                    errors.add(ex);
                }
            }, executor);
            CompletableFuture<Void> b = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    second.get();
                    successes.incrementAndGet();
                } catch (Exception ex) {
                    errors.add(ex);
                }
            }, executor);
            start.countDown();
            CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
            return new Outcomes(successes.get(), errors.size(), List.copyOf(errors));
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    private record Outcomes(int successes, int failures, List<Throwable> errors) {}
}
