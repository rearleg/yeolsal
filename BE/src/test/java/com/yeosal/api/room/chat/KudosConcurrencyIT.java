package com.yeosal.api.room.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 3.5 AC11 BE-11.4 — concurrency contract. Two parallel
 * {@code sendKudos} calls compete inside the partial unique index
 * {@code ux_kudos_one_per_day}; exactly one wins, the other surfaces
 * {@link KudosAlreadySentTodayException} (or a {@link
 * DataIntegrityViolationException} translated by the service-layer
 * catch — both forms are acceptable per AC11 since the wire shape is
 * the same).
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true} (project-context rule:
 * H2 forbidden — partial unique indexes + jsonb require Postgres).
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class KudosConcurrencyIT {

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

    @Autowired private KudosService kudosService;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private FriendshipRepository friendships;
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
                        + "revival_events, friendships, "
                        + "notification_log, chat_messages, daily_entries, reflections, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("two parallel sendKudos for same (sender, target) — exactly one row lands; loser surfaces typed exception")
    void parallelSends_collapseToExactlyOneRow() throws Exception {
        Fixture f = seed();
        long roomId = f.room.getId();
        long senderId = f.sender.getId();
        long targetId = f.target.getId();
        forceRed(targetId, roomId);

        Outcomes outcomes = race(
                () -> kudosService.sendKudos(roomId, f.sender, targetId, "테스트"),
                () -> kudosService.sendKudos(roomId, f.sender, targetId, "테스트"));

        assertThat(outcomes.successes).isEqualTo(1);
        assertThat(outcomes.failures).isEqualTo(1);
        assertThat(outcomes.errors)
                .singleElement()
                .satisfies(ex -> assertThat(ex)
                        .isInstanceOfAny(
                                KudosAlreadySentTodayException.class,
                                DataIntegrityViolationException.class));

        Long kudosRows = jdbc.queryForObject(
                "select count(*) from chat_messages where kind = 'KUDOS' and sender_user_id = ?",
                Long.class, senderId);
        assertThat(kudosRows).isEqualTo(1L);

        String payloadTarget = jdbc.queryForObject(
                "select payload->>'targetUserId' from chat_messages "
                        + "where kind = 'KUDOS' and sender_user_id = ? limit 1",
                String.class, senderId);
        assertThat(payloadTarget).isEqualTo(String.valueOf(targetId));
    }

    // ----- helpers -----

    private record Fixture(User sender, User target, Room room) {}

    private Fixture seed() {
        User owner = users.save(new User(
                "owner-kudos@example.com", "owner", "h", AuthProvider.EMAIL));
        User alice = users.save(new User(
                "alice@example.com", "alice", "h", AuthProvider.EMAIL));
        User bob = users.save(new User(
                "bob@example.com", "bob", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("kudos-it room", owner));
        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMember(room, owner, RoomRole.OWNER, joinedAt);
        seedMember(room, alice, RoomRole.MEMBER, joinedAt);
        seedMember(room, bob, RoomRole.MEMBER, joinedAt);
        survivalStateService.initializeOnJoin(room, owner, joinedAt);
        survivalStateService.initializeOnJoin(room, alice, joinedAt);
        survivalStateService.initializeOnJoin(room, bob, joinedAt);
        Friendship f = new Friendship(alice, bob);
        f.setStatus(FriendshipStatus.ACCEPTED);
        friendships.save(f);
        return new Fixture(alice, bob, room);
    }

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    /**
     * Flip the target's survival state to RED via reflection — mirrors
     * {@code RevivalConcurrencyIT.forceRed} since {@link SurvivalState}
     * mutators are package-private to {@code survival/}.
     */
    private void forceRed(long userId, long roomId) {
        Instant eliminatedAt = Instant.now().minus(Duration.ofHours(12));
        tx.executeWithoutResult(t -> {
            SurvivalState state = survivalStates
                    .findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow();
            setField(state, "status", SurvivalStatus.RED);
            setField(state, "lastStateChangeAt", eliminatedAt);
            setField(state, "eliminatedAt", eliminatedAt);
            setField(state, "broadVisibilityAt", eliminatedAt.plus(Duration.ofHours(24)));
            survivalStates.save(state);
        });
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
