package com.yeosal.api.survival;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-stack integration test for Story 1.3 — privacy-filtered survival
 * roster endpoint. Opt-in via {@code -Dyeosal.boot-smoke=true}, mirroring
 * {@link SurvivalStateEvaluatorIT} and {@link
 * com.yeosal.api.room.RoomControllerIT}.
 *
 * <p>Three viewers exercise the AC3/AC4/AC5 branches against the same
 * RED-during-cooldown row:
 * <ul>
 *   <li><strong>bob</strong> (non-leader, non-self) — sees Carol masked as ACTIVE.</li>
 *   <li><strong>alice</strong> (leader) — sees Carol's true RED status.</li>
 *   <li><strong>carol</strong> (self) — sees her own RED status.</li>
 *   <li><strong>dave</strong> (outsider) — receives 403 FORBIDDEN.</li>
 * </ul>
 *
 * <p>A second test forces the cooldown to be elapsed and verifies bob now
 * sees the true RED status (AC6 broad-visibility).
 *
 * <p>Package placement is intentional — package-private setters on
 * {@link SurvivalState} (Story 1.1 invariant: only
 * {@link SurvivalStateService} drives transitions) must be reachable for
 * the RED-seeding helper. Same trick as {@link SurvivalStateEvaluatorIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class SurvivalStateRosterIT {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Wipe the schema between tests. Without this, the second test's
     * {@link #seed()} call collides with the first test's persisted users on
     * the {@code users.email} UNIQUE constraint (Story 1.3 review finding #4).
     * The Testcontainers Postgres container is class-scoped, so data leaks
     * across {@code @Test} methods unless we truncate explicitly.
     */
    @BeforeEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "survival_state, streak_freezes, personal_points_ledger, "
                        + "pending_realtime_broadcasts, room_rule_versions, room_point_pool, "
                        + "notification_log, chat_messages, daily_entries, reflections, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("non-leader, non-self viewer sees RED-during-cooldown masked as ACTIVE (AC3)")
    void roster_nonLeaderNonSelf_seesMasked_duringCooldown() throws Exception {
        Fixture f = seed();
        forceRed(f.carol().getId(), f.room().getId(),
                Instant.now().minus(Duration.ofHours(22)),
                Instant.now().plus(Duration.ofHours(2)));

        // Per Story 1.3 AC3 the masked DTO emits these three fields as
        // explicit nulls (Jackson default includes nulls). A JsonPath filter
        // expression returns a list, so `.doesNotExist()` (which requires
        // path absence) does not match `[null]`. The semantic check is
        // "every value at this path is null".
        mockMvc.perform(get("/api/v1/rooms/{id}/survival", f.room().getId())
                        .with(authentication(authFor(f.bob()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].status")
                        .value("ACTIVE"))
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].eliminatedAt")
                        .value(everyItem(nullValue())))
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].broadVisibilityAt")
                        .value(everyItem(nullValue())))
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].lastStateChangeAt")
                        .value(everyItem(nullValue())));
    }

    @Test
    @DisplayName("leader sees Carol's true RED status during cooldown (AC4)")
    void roster_leader_seesActualRed_duringCooldown() throws Exception {
        Fixture f = seed();
        forceRed(f.carol().getId(), f.room().getId(),
                Instant.now().minus(Duration.ofHours(22)),
                Instant.now().plus(Duration.ofHours(2)));

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", f.room().getId())
                        .with(authentication(authFor(f.alice()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].status")
                        .value("RED"))
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].eliminatedAt")
                        .exists())
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].broadVisibilityAt")
                        .exists());
    }

    @Test
    @DisplayName("self sees own true RED status during cooldown (AC5)")
    void roster_self_seesOwnActualRed_duringCooldown() throws Exception {
        Fixture f = seed();
        forceRed(f.carol().getId(), f.room().getId(),
                Instant.now().minus(Duration.ofHours(22)),
                Instant.now().plus(Duration.ofHours(2)));

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", f.room().getId())
                        .with(authentication(authFor(f.carol()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].status")
                        .value("RED"))
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].eliminatedAt")
                        .exists());
    }

    @Test
    @DisplayName("non-leader, non-self viewer sees Carol's true RED after cooldown elapsed (AC6)")
    void roster_nonLeaderNonSelf_seesActualRed_afterCooldown() throws Exception {
        Fixture f = seed();
        // broadVisibilityAt in the past → cooldown elapsed.
        forceRed(f.carol().getId(), f.room().getId(),
                Instant.now().minus(Duration.ofDays(2)),
                Instant.now().minus(Duration.ofHours(1)));

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", f.room().getId())
                        .with(authentication(authFor(f.bob()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].status")
                        .value("RED"))
                .andExpect(jsonPath("$.data[?(@.userId == " + f.carol().getId() + ")].eliminatedAt")
                        .exists());
    }

    @Test
    @DisplayName("non-member viewer → 403 FORBIDDEN + error.code=FORBIDDEN (AC2)")
    void roster_nonMember_returns403() throws Exception {
        Fixture f = seed();

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", f.room().getId())
                        .with(authentication(authFor(f.dave()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("missing room → 404 NOT_FOUND + error.code=NOT_FOUND (AC2)")
    void roster_missingRoom_returns404() throws Exception {
        Fixture f = seed();

        mockMvc.perform(get("/api/v1/rooms/{id}/survival", 9999999L)
                        .with(authentication(authFor(f.alice()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ----- helpers -----

    /**
     * Build a {@link UserPrincipal}-backed {@link Authentication} matching the
     * production {@code CurrentUser.require(...)} contract. Plain
     * {@code @WithMockUser} produces a Spring Security {@code User} principal
     * which fails the {@code instanceof UserPrincipal} guard and 401s before
     * exercising controller behavior (Story 1.3 review finding #2).
     */
    private static Authentication authFor(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail());
        return new UsernamePasswordAuthenticationToken(
                principal, "", principal.getAuthorities());
    }

    private record Fixture(User alice, User bob, User carol, User dave, Room room) {}

    private Fixture seed() {
        User alice = users.save(new User(
                "alice-rosterit@example.com", "Alice", "h", AuthProvider.EMAIL));
        User bob = users.save(new User(
                "bob-rosterit@example.com", "Bob", "h", AuthProvider.EMAIL));
        User carol = users.save(new User(
                "carol-rosterit@example.com", "Carol", "h", AuthProvider.EMAIL));
        User dave = users.save(new User(
                "dave-rosterit@example.com", "Dave", "h", AuthProvider.EMAIL));

        Room room = rooms.save(new Room("roster-it room", alice));
        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMember(room, alice, RoomRole.OWNER, joinedAt);
        seedMember(room, bob, RoomRole.MEMBER, joinedAt);
        seedMember(room, carol, RoomRole.MEMBER, joinedAt);
        // dave intentionally absent from room_members

        survivalStateService.initializeOnJoin(room, alice, joinedAt);
        survivalStateService.initializeOnJoin(room, bob, joinedAt);
        survivalStateService.initializeOnJoin(room, carol, joinedAt);

        return new Fixture(alice, bob, carol, dave, room);
    }

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private void forceRed(
            long userId, long roomId, Instant eliminatedAt, Instant broadVisibilityAt) {
        tx.executeWithoutResult(t -> {
            SurvivalState state = survivalStates
                    .findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow();
            state.setStatus(SurvivalStatus.RED);
            state.setLastStateChangeAt(eliminatedAt);
            state.setEliminatedAt(eliminatedAt);
            state.setBroadVisibilityAt(broadVisibilityAt);
            survivalStates.save(state);
        });
    }
}
