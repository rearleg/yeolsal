package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
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
 * Story 5.3 AC3 / AC11 row 2 — repository-slice coverage for the new
 * {@link RoomMemberRepository#findLongestTenuredActiveCandidates(long, long)}
 * JPQL. Asserts the EXISTS subquery's strict ACTIVE filter, the joinedAt
 * ASC + id ASC deterministic tiebreaker (Trap #2), and the explicit
 * excluded-userId predicate.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true} (mirrors Story 5.2's
 * {@link RoomMemberCapPromotionIT} / {@link V13MigrationIT} pattern — no
 * {@code @DataJpaTest} precedent exists in this repo).
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RoomMemberRepositoryFindLongestTenuredActiveTest {

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
    @Autowired private SurvivalStateService survivalState;

    private User leader;
    private Room room;

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
        leader = users.save(new User(
                "leader-5-3@example.com", "leader", "h", AuthProvider.EMAIL));
        room = rooms.save(new Room("auto-leader test room", leader));
    }

    @Test
    @DisplayName("returns empty when no member has survival_state.status = ACTIVE")
    void noActiveCandidates_returnsEmpty() {
        seedActiveMember(leader, Instant.parse("2026-01-01T00:00:00Z"));
        User redMember = seedUser("red@example.com", "red");
        User yellowMember = seedUser("yellow@example.com", "yellow");
        User spectatorMember = seedUser("spec@example.com", "spec");
        seedActiveMember(redMember, Instant.parse("2026-01-02T00:00:00Z"));
        seedActiveMember(yellowMember, Instant.parse("2026-01-03T00:00:00Z"));
        seedActiveMember(spectatorMember, Instant.parse("2026-01-04T00:00:00Z"));
        forceStatus(redMember.getId(), "RED");
        forceStatus(yellowMember.getId(), "YELLOW");
        forceStatus(spectatorMember.getId(), "SPECTATOR");

        List<RoomMember> result = roomMembers
                .findLongestTenuredActiveCandidates(room.getId(), leader.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns ACTIVE candidates ordered by joined_at ASC (earliest first)")
    void picksMinJoinedAtActive() {
        seedActiveMember(leader, Instant.parse("2026-01-01T00:00:00Z"));
        User newest = seedUser("newest@example.com", "newest");
        User oldest = seedUser("oldest@example.com", "oldest");
        User middle = seedUser("middle@example.com", "middle");
        // Seed in NOT-chronological order so the order-by clause is the
        // load-bearing assertion.
        seedActiveMember(newest, Instant.parse("2026-01-04T00:00:00Z"));
        seedActiveMember(oldest, Instant.parse("2026-01-02T00:00:00Z"));
        seedActiveMember(middle, Instant.parse("2026-01-03T00:00:00Z"));

        List<RoomMember> result = roomMembers
                .findLongestTenuredActiveCandidates(room.getId(), leader.getId());

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getUser().getId()).isEqualTo(oldest.getId());
        assertThat(result.get(1).getUser().getId()).isEqualTo(middle.getId());
        assertThat(result.get(2).getUser().getId()).isEqualTo(newest.getId());
    }

    @Test
    @DisplayName("tiebreaker on room_members.id ASC when joined_at is identical")
    void tiebreakerOnIdAsc() {
        seedActiveMember(leader, Instant.parse("2026-01-01T00:00:00Z"));
        User first = seedUser("first@example.com", "first");
        User second = seedUser("second@example.com", "second");
        Instant sameInstant = Instant.parse("2026-02-15T12:00:00Z");
        RoomMember firstMember = seedActiveMember(first, sameInstant);
        RoomMember secondMember = seedActiveMember(second, sameInstant);

        List<RoomMember> result = roomMembers
                .findLongestTenuredActiveCandidates(room.getId(), leader.getId());

        assertThat(firstMember.getId()).isLessThan(secondMember.getId());
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(firstMember.getId());
        assertThat(result.get(1).getId()).isEqualTo(secondMember.getId());
    }

    @Test
    @DisplayName("excludes the explicitly excluded user_id even if they are ACTIVE")
    void excludesPassedUserId() {
        seedActiveMember(leader, Instant.parse("2026-01-01T00:00:00Z"));
        User other = seedUser("other@example.com", "other");
        seedActiveMember(other, Instant.parse("2026-01-02T00:00:00Z"));

        List<RoomMember> withExclusion = roomMembers
                .findLongestTenuredActiveCandidates(room.getId(), leader.getId());
        List<RoomMember> withoutExclusion = roomMembers
                .findLongestTenuredActiveCandidates(room.getId(), 0L);

        assertThat(withExclusion).hasSize(1);
        assertThat(withExclusion.get(0).getUser().getId()).isEqualTo(other.getId());
        assertThat(withoutExclusion).hasSize(2);
        // Leader joined earliest — would have won absent the exclusion.
        assertThat(withoutExclusion.get(0).getUser().getId()).isEqualTo(leader.getId());
    }

    // ----- helpers -----

    private User seedUser(String email, String nickname) {
        return users.save(new User(email, nickname, "h", AuthProvider.EMAIL));
    }

    private RoomMember seedActiveMember(User user, Instant joinedAt) {
        RoomRole role = user.getId().equals(leader.getId()) ? RoomRole.OWNER : RoomRole.MEMBER;
        RoomMember member = new RoomMember(room, user, role);
        member.setJoinedAt(joinedAt);
        RoomMember saved = roomMembers.save(member);
        survivalState.initializeOnJoin(room, user, joinedAt);
        return saved;
    }

    private void forceStatus(long userId, String status) {
        jdbc.update(
                "UPDATE survival_state SET status = ?, last_state_change_at = now() "
                        + "WHERE room_id = ? AND user_id = ?",
                status, room.getId(), userId);
    }
}
