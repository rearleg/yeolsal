package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
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
 * Story 5.2 AC5 — full-stack lazy promotion smoke through Spring's
 * {@code REQUIRES_NEW} tx manager. Stages a pending cap edit on a real
 * PostgreSQL row, then invokes
 * {@link RoomCapPromotionService#promotePendingCapIfDue(long)} across the
 * before/after month boundary.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RoomMemberCapPromotionIT {

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
    @Autowired private RoomCapPromotionService capPromotion;
    @Autowired private RoomService roomService;

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
    @DisplayName("Future-month pending → no flush; pending row preserved")
    void pendingFuture_noFlush() {
        long roomId = seedRoomWithPending(20, "2099-12");

        boolean promoted = capPromotion.promotePendingCapIfDue(roomId);

        assertThat(promoted).isFalse();
        Short pending = jdbc.queryForObject(
                "select pending_max_members from rooms where id = ?",
                Short.class, roomId);
        assertThat(pending).isEqualTo((short) 20);
    }

    @Test
    @DisplayName("Past-month pending → flush + clear pending; idempotent on re-entry")
    void pendingDue_flushesAndIdempotent() {
        long roomId = seedRoomWithPending(20, "2000-01");

        boolean firstPromote = capPromotion.promotePendingCapIfDue(roomId);
        boolean secondPromote = capPromotion.promotePendingCapIfDue(roomId);

        assertThat(firstPromote).isTrue();
        assertThat(secondPromote).isFalse();
        Short newCap = jdbc.queryForObject(
                "select max_members from rooms where id = ?",
                Short.class, roomId);
        Short pending = jdbc.queryForObject(
                "select pending_max_members from rooms where id = ?",
                Short.class, roomId);
        String pendingMonth = jdbc.queryForObject(
                "select pending_max_members_effective_from_month from rooms where id = ?",
                String.class, roomId);
        assertThat(newCap).isEqualTo((short) 20);
        assertThat(pending).isNull();
        assertThat(pendingMonth).isNull();
    }

    @Test
    @DisplayName("RoomService.requireRoom boundary promotes due cap and returns refreshed summary")
    void requireRoom_promotesAndReturnsFreshRoom() {
        long roomId = seedRoomWithPending(20, "2000-01");

        Room promoted = roomService.requireRoom(roomId);

        assertThat(promoted.getMaxMembers()).isEqualTo((short) 20);
        assertThat(promoted.getPendingMaxMembers()).isNull();
        assertThat(promoted.getPendingMaxMembersEffectiveFromMonth()).isNull();
    }

    private long seedRoomWithPending(int pendingCap, String effectiveMonth) {
        User owner = users.save(new User(
                "owner-cap-it@example.com", "owner-cap-it", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("cap-it room", owner));
        room.setPendingMaxMembers((short) pendingCap);
        room.setPendingMaxMembersEffectiveFromMonth(effectiveMonth);
        rooms.save(room);
        return room.getId();
    }
}
