package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 7.1 AC10 — opt-in integration coverage. Mirrors
 * {@code PreviewCardEndToEndIT}: real Postgres via Testcontainers, real
 * Batik PNG transcode, real Flyway schema. Disabled by default
 * ({@code yeosal.boot-smoke=true} opt-in) so {@code ./gradlew test}
 * stays Docker-free; PR-CI enables it.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class FinalThreeServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("yeosal")
                    .withUsername("yeosal")
                    .withPassword("yeosal");

    @TempDir
    static Path postersDir;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("yeosal.share.posters-dir", postersDir::toString);
        registry.add("yeosal.share.preview-card-base", () -> "http://test.local/yeolsal");
    }

    @Autowired private FinalThreeService finalThreeService;
    @Autowired private FinalThreePosterRepository posterRepository;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void truncate() {
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "final_three_posters, survival_state, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("happy path — five ACTIVE survivors produces persisted row with non-empty SVG + PNG bytes")
    void happyPath_fivesurvivors_persists() {
        User alice = users.save(
                new User("alice@example.com", "alice", "hash", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("우리 방", alice));
        roomMembers.save(new RoomMember(room, alice, RoomRole.OWNER));
        survivalStates.save(new SurvivalState(room, alice, null));

        for (int i = 0; i < 4; i++) {
            User u = users.save(new User(
                    "u%d@example.com".formatted(i), "user-%d".formatted(i),
                    "hash", AuthProvider.EMAIL));
            roomMembers.save(new RoomMember(room, u, RoomRole.MEMBER));
            survivalStates.save(new SurvivalState(room, u, null));
        }

        Optional<FinalThreePoster> result =
                finalThreeService.generatePoster(room.getId(), YearMonth.of(2026, 6));

        assertThat(result).isPresent();
        FinalThreePoster saved = result.get();
        assertThat(saved.getSvgText()).contains("<svg");
        assertThat(saved.getPngUrl()).endsWith("/posters/" + room.getId() + "-2026-06.png");
        assertThat(posterRepository.existsById(
                new FinalThreePosterId(room.getId(), "2026-06"))).isTrue();
    }

    @Test
    @DisplayName("zero-survivor path — chat row inserted, no poster row")
    void zeroSurvivors_chatFallback_noPosterRow() {
        User alice = users.save(
                new User("alice@example.com", "alice", "hash", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("빈 방", alice));
        roomMembers.save(new RoomMember(room, alice, RoomRole.OWNER));
        // NO survival_state row -> survivors list is empty.

        Optional<FinalThreePoster> result =
                finalThreeService.generatePoster(room.getId(), YearMonth.of(2026, 6));

        assertThat(result).isEmpty();
        assertThat(posterRepository.existsById(
                new FinalThreePosterId(room.getId(), "2026-06"))).isFalse();

        Integer chatCount = jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM'"
                        + " AND body LIKE '이번 달은 아무도%'",
                Integer.class, room.getId());
        assertThat(chatCount).isEqualTo(1);
    }

    @Test
    @DisplayName("idempotent re-invocation — second call returns existing row, no new chat or write")
    void idempotent_secondCall_noRework() {
        User alice = users.save(
                new User("alice@example.com", "alice", "hash", AuthProvider.EMAIL));
        Room room = rooms.save(new Room("우리 방", alice));
        roomMembers.save(new RoomMember(room, alice, RoomRole.OWNER));
        survivalStates.save(new SurvivalState(room, alice, null));

        Optional<FinalThreePoster> first =
                finalThreeService.generatePoster(room.getId(), YearMonth.of(2026, 6));
        Optional<FinalThreePoster> second =
                finalThreeService.generatePoster(room.getId(), YearMonth.of(2026, 6));

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        // Same generatedAt → second call hit the short-circuit, not a re-save.
        assertThat(second.get().getGeneratedAt()).isEqualTo(first.get().getGeneratedAt());
    }
}
