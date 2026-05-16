package com.yeosal.api.survival;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.Reflection;
import com.yeosal.api.daily.ReflectionRepository;
import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
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
 * Full-stack integration test for Story 2.3 — record-visibility opt-in
 * redaction on the profile read endpoints. Opt-in via
 * {@code -Dyeosal.boot-smoke=true}, mirroring {@link
 * com.yeosal.api.survival.SurvivalStateRosterIT}.
 *
 * <p>Pins AC3/AC6/AC7 in production-shaped infrastructure: a SPECTATOR
 * target's grass and reflections are redacted to empty lists for a
 * room-mate viewer when no pref row exists (default-private), become
 * visible once the target opts in, redact again on opt-out, and stay
 * visible when target is ACTIVE regardless of toggle (Story 2.3 forward-
 * compat).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class ProfileVisibilityRedactionIT {

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
    @Autowired private DailyEntryRepository dailyEntries;
    @Autowired private ReflectionRepository reflections;
    @Autowired private FriendshipRepository friendships;
    @Autowired private RecordVisibilityPrefRepository visibilityPrefs;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "record_visibility_prefs, survival_state, streak_freezes, "
                        + "personal_points_ledger, pending_realtime_broadcasts, "
                        + "room_rule_versions, room_point_pool, notification_log, "
                        + "chat_messages, reflections, daily_entries, "
                        + "room_members, rooms, friendships, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("SPECTATOR target with no pref row → grass + reflections redacted to empty (AC3 + AC6)")
    void spectator_defaultPrivate_redactsBoth() throws Exception {
        Fixture f = seed();
        forceSpectator(f.target.getId(), f.room.getId());
        seedDailyAndReflection(f.target);

        mockMvc.perform(get("/api/v1/profiles/{userId}/grass", f.target.getId())
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-10")
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.intensity > 0)].date").isEmpty());

        mockMvc.perform(get("/api/v1/profiles/{userId}/reflections", f.target.getId())
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("SPECTATOR target opted-in → reflections visible (AC3)")
    void spectator_optedIn_recordsVisible() throws Exception {
        Fixture f = seed();
        forceSpectator(f.target.getId(), f.room.getId());
        seedDailyAndReflection(f.target);
        upsertPref(f.target.getId(), f.room.getId(), true);

        mockMvc.perform(get("/api/v1/profiles/{userId}/reflections", f.target.getId())
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("opt-in → opt-out flow: records become hidden again")
    void spectator_optedInThenOut_redactsAgain() throws Exception {
        Fixture f = seed();
        forceSpectator(f.target.getId(), f.room.getId());
        seedDailyAndReflection(f.target);
        upsertPref(f.target.getId(), f.room.getId(), true);
        upsertPref(f.target.getId(), f.room.getId(), false);

        mockMvc.perform(get("/api/v1/profiles/{userId}/reflections", f.target.getId())
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("self-view of /me/grass always sees own records (AC3 self-view bypass)")
    void self_view_alwaysFull() throws Exception {
        Fixture f = seed();
        forceSpectator(f.target.getId(), f.room.getId());
        seedDailyAndReflection(f.target);

        mockMvc.perform(get("/api/v1/profiles/me/grass")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-10")
                        .with(authentication(authFor(f.target))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("ACTIVE target with pref=false → records visible (AC7 spectator-only redaction)")
    void active_target_recordsVisibleRegardlessOfToggle() throws Exception {
        Fixture f = seed();
        seedDailyAndReflection(f.target);
        upsertPref(f.target.getId(), f.room.getId(), false);

        mockMvc.perform(get("/api/v1/profiles/{userId}/reflections", f.target.getId())
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/rooms/{id}/today scrubs SPECTATOR + opted-out target fields (AC3 group-mode)")
    void group_today_spectator_optedOut_scrubsFields() throws Exception {
        Fixture f = seed();
        forceSpectator(f.target.getId(), f.room.getId());
        seedDailyAndReflection(f.target);

        mockMvc.perform(get("/api/v1/rooms/{id}/today", f.room.getId())
                        .param("date", "2026-04-05")
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data[?(@.userId == " + f.target.getId() + ")].goal")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(""))))
                .andExpect(jsonPath(
                        "$.data[?(@.userId == " + f.target.getId() + ")].goalSet")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath(
                        "$.data[?(@.userId == " + f.target.getId() + ")].reflectionSubmitted")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
    }

    @Test
    @DisplayName("GET /api/v1/rooms/{id}/today shows SPECTATOR target's fields once opted-in (AC3 group-mode)")
    void group_today_spectator_optedIn_visible() throws Exception {
        Fixture f = seed();
        forceSpectator(f.target.getId(), f.room.getId());
        seedDailyAndReflection(f.target);
        upsertPref(f.target.getId(), f.room.getId(), true);

        mockMvc.perform(get("/api/v1/rooms/{id}/today", f.room.getId())
                        .param("date", "2026-04-05")
                        .with(authentication(authFor(f.viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data[?(@.userId == " + f.target.getId() + ")].goal")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("오늘 목표"))));
    }

    // ----- helpers -----

    private static Authentication authFor(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail());
        return new UsernamePasswordAuthenticationToken(
                principal, "", principal.getAuthorities());
    }

    private record Fixture(User viewer, User target, Room room) {}

    private Fixture seed() {
        User viewer = users.save(new User(
                "viewer-vredact@example.com", "Viewer", "h", AuthProvider.EMAIL));
        User target = users.save(new User(
                "target-vredact@example.com", "Target", "h", AuthProvider.EMAIL));

        Room room = rooms.save(new Room("vredact room", viewer));
        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMember(room, viewer, RoomRole.OWNER, joinedAt);
        seedMember(room, target, RoomRole.MEMBER, joinedAt);

        survivalStateService.initializeOnJoin(room, viewer, joinedAt);
        survivalStateService.initializeOnJoin(room, target, joinedAt);

        Friendship f = new Friendship(viewer, target);
        f.setStatus(FriendshipStatus.ACCEPTED);
        friendships.save(f);

        return new Fixture(viewer, target, room);
    }

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private void seedDailyAndReflection(User user) {
        tx.executeWithoutResult(t -> {
            DailyEntry entry = dailyEntries.save(
                    new DailyEntry(user, LocalDate.parse("2026-04-05"), "오늘 목표"));
            Reflection r = new Reflection(entry, "오늘 회고 본문");
            reflections.save(r);
        });
    }

    private void forceSpectator(long userId, long roomId) {
        tx.executeWithoutResult(t -> {
            SurvivalState state = survivalStates
                    .findByRoomIdAndUserId(roomId, userId)
                    .orElseThrow();
            state.setStatus(SurvivalStatus.SPECTATOR);
            state.setLastStateChangeAt(Instant.now());
            state.setEliminatedAt(Instant.now().minusSeconds(3600));
            state.setBroadVisibilityAt(Instant.now().minusSeconds(60));
            survivalStates.save(state);
        });
    }

    private void upsertPref(long userId, long roomId, boolean share) {
        tx.executeWithoutResult(t -> {
            visibilityPrefs.upsertShareOnElimination(userId, roomId, share);
        });
    }
}
