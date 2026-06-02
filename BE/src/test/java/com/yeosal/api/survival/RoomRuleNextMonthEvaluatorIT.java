package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 5.1 AC9 — contract-integrity IT for the daily evaluator's rule
 * read-through. Opt-in via {@code -Dyeosal.boot-smoke=true} (mirrors
 * {@link SurvivalStateEvaluatorIT}) so plain {@code ./gradlew test} stays
 * fast and Docker-free.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class RoomRuleNextMonthEvaluatorIT {

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
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private RoomRuleVersionRepository ruleVersions;

    @Test
    @DisplayName("AC9-1 — April evaluator reads the APRIL rule (weekendInclude=true) even after a May edit lands")
    void evaluator_inAprilStillReadsAprilRule() {
        User leader = users.save(new User("leader-ac9-1@example.com", "Leader", "h", AuthProvider.EMAIL));
        User member = users.save(new User("member-ac9-1@example.com", "Member", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("ac9-april-room", leader));
        Instant joinedAt = Instant.parse("2026-03-01T00:00:00Z");
        seedMember(room, leader, RoomRole.OWNER, joinedAt);
        seedMember(room, member, RoomRole.MEMBER, joinedAt);

        ruleVersions.save(buildRow(room.getId(), "2026-04", true, leader.getId()));
        ruleVersions.save(buildRow(room.getId(), "2026-05", false, leader.getId()));

        survivalStateService.initializeOnJoin(room, leader, joinedAt);
        survivalStateService.initializeOnJoin(room, member, joinedAt);

        LocalDate friday = LocalDate.of(2026, 4, 24);
        survivalStateService.evaluateRoom(room.getId(), friday);

        SurvivalState memberState = survivalStates
                .findByRoomIdAndUserId(room.getId(), member.getId())
                .orElseThrow();
        assertThat(memberState.getStatus())
                .as("April rule weekendInclude=true → Friday miss escalates ACTIVE → YELLOW")
                .isEqualTo(SurvivalStatus.YELLOW);
    }

    @Test
    @DisplayName("AC9-2 — May evaluator reads the NEW MAY rule (weekendInclude=false) → Saturday skipped")
    void evaluator_inMayReadsNewRule_saturdaySkipped() {
        User leader = users.save(new User("leader-ac9-2@example.com", "Leader", "h", AuthProvider.EMAIL));
        User member = users.save(new User("member-ac9-2@example.com", "Member", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("ac9-may-room", leader));
        Instant joinedAt = Instant.parse("2026-03-01T00:00:00Z");
        seedMember(room, leader, RoomRole.OWNER, joinedAt);
        seedMember(room, member, RoomRole.MEMBER, joinedAt);

        ruleVersions.save(buildRow(room.getId(), "2026-04", true, leader.getId()));
        ruleVersions.save(buildRow(room.getId(), "2026-05", false, leader.getId()));

        survivalStateService.initializeOnJoin(room, leader, joinedAt);
        survivalStateService.initializeOnJoin(room, member, joinedAt);

        LocalDate saturday = LocalDate.of(2026, 5, 2);
        survivalStateService.evaluateRoom(room.getId(), saturday);

        SurvivalState memberState = survivalStates
                .findByRoomIdAndUserId(room.getId(), member.getId())
                .orElseThrow();
        assertThat(memberState.getStatus())
                .as("May rule weekendInclude=false → Saturday skipped, status stays ACTIVE")
                .isEqualTo(SurvivalStatus.ACTIVE);
    }

    @Test
    @Transactional
    @DisplayName("native upsert inserts and replaces the same effective-month row")
    void upsertRule_sameMonth_replacesPayload() {
        User leader = users.save(new User("leader-upsert@example.com", "Leader", "h", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("upsert-room", leader));

        assertThat(ruleVersions.upsertRule(
                room.getId(), "2026-05",
                "{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":true}",
                leader.getId())).isEqualTo(1);
        long firstId = ruleVersions
                .findByRoomIdAndEffectiveFromMonth(room.getId(), "2026-05")
                .orElseThrow()
                .getId();

        assertThat(ruleVersions.upsertRule(
                room.getId(), "2026-05",
                "{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":false}",
                leader.getId())).isEqualTo(1);
        RoomRuleVersion replaced = ruleVersions
                .findByRoomIdAndEffectiveFromMonth(room.getId(), "2026-05")
                .orElseThrow();

        assertThat(replaced.getId()).isEqualTo(firstId);
        assertThat(replaced.getRulePayload().path("weekendInclude").asBoolean()).isFalse();
    }

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private RoomRuleVersion buildRow(long roomId, String month, boolean weekendInclude, long createdBy) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("preset", "DAILY_UPDATE");
        payload.put("weekendInclude", weekendInclude);
        return new RoomRuleVersion(roomId, month, payload, createdBy);
    }
}
