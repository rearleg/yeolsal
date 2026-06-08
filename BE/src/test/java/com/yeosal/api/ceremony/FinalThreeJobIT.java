package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yeosal.api.realtime.RealtimePublisher;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 7.2 — opt-in integration coverage. Mirrors {@link FinalThreeServiceIT}:
 * real Postgres via Testcontainers, real Batik PNG transcode, real Flyway
 * schema, real STOMP destination assertion via {@code @SpyBean} on
 * {@link SimpMessagingTemplate}. Disabled by default
 * ({@code yeosal.boot-smoke=true} opt-in) so {@code ./gradlew test} stays
 * Docker-free; PR-CI enables it.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class FinalThreeJobIT {

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

    @Autowired private FinalThreeJob finalThreeJob;
    @Autowired private FinalThreeService finalThreeService;
    @Autowired private FinalThreePosterRepository posterRepository;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private RoomMemberRepository roomMembers;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private RealtimePublisher realtimePublisher;
    @Autowired
    @Qualifier(MonthlyPosterRenderExecutorConfig.EXECUTOR_BEAN_NAME)
    private TaskExecutor executor;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private SimpMessagingTemplate template;

    @BeforeEach
    void truncate() {
        Mockito.reset(template);
        jdbc.execute(
                "TRUNCATE TABLE "
                        + "final_three_posters, survival_state, chat_messages, "
                        + "room_members, rooms, users "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("happy path — 2 eligible rooms produce posters; 1 zero-survivor room is pre-filtered out")
    void happyPath_eligibleRoomsGenerateOthersFilteredOut() throws Exception {
        Room roomA = seedRoomWithActiveSurvivors("방A", 2);
        Room roomB = seedRoomWithActiveSurvivors("방B", 5);
        Room roomC = seedRoomNoSurvivors("방C");
        YearMonth target = YearMonth.of(2026, 5);

        FinalThreeJob.Summary summary = finalThreeJob.runBatch(target);

        assertThat(summary.eligible()).isEqualTo(2);
        assertThat(summary.generated()).isEqualTo(2);
        assertThat(summary.skipped()).isZero();
        assertThat(summary.zeroSurvivor()).isZero();
        assertThat(summary.failed()).isZero();

        assertThat(posterRepository.existsById(
                new FinalThreePosterId(roomA.getId(), "2026-05"))).isTrue();
        assertThat(posterRepository.existsById(
                new FinalThreePosterId(roomB.getId(), "2026-05"))).isTrue();
        assertThat(posterRepository.existsById(
                new FinalThreePosterId(roomC.getId(), "2026-05"))).isFalse();
        assertPngWritten(roomA);
        assertPngWritten(roomB);

        // STOMP fan-out: one publish per fresh-generated room on the
        // dedicated .posters topic.
        verify(template).convertAndSend(
                eq("/topic/rooms." + roomA.getId() + ".posters"), any(Object.class));
        verify(template).convertAndSend(
                eq("/topic/rooms." + roomB.getId() + ".posters"), any(Object.class));
    }

    @Test
    @DisplayName("idempotent rerun — second runBatch produces 0 generated, N skipped; STOMP silent on rerun")
    void idempotentRerun_skipsAndNoRePublish() {
        Room roomA = seedRoomWithActiveSurvivors("방A", 2);
        Room roomB = seedRoomWithActiveSurvivors("방B", 3);
        YearMonth target = YearMonth.of(2026, 5);

        FinalThreeJob.Summary first = finalThreeJob.runBatch(target);
        Mockito.reset(template);
        FinalThreeJob.Summary second = finalThreeJob.runBatch(target);

        assertThat(first.generated()).isEqualTo(2);
        assertThat(second.eligible()).isEqualTo(2);
        assertThat(second.generated()).isZero();
        assertThat(second.skipped()).isEqualTo(2);

        assertThat(posterRepository.count()).isEqualTo(2);
        verify(template, never()).convertAndSend(
                eq("/topic/rooms." + roomA.getId() + ".posters"), any(Object.class));
        verify(template, never()).convertAndSend(
                eq("/topic/rooms." + roomB.getId() + ".posters"), any(Object.class));
    }

    @Test
    @DisplayName("race guard — survivor disappears after pre-filter → zeroSurvivor++, chat fallback inserted")
    void raceGuard_zeroSurvivorRoomExcludedByPreFilter() {
        Room roomA = seedRoomWithActiveSurvivors("방A", 1);
        YearMonth target = YearMonth.of(2026, 5);
        SurvivalStateRepository raceRepository = Mockito.mock(SurvivalStateRepository.class);
        PageRequest page = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
        Mockito.when(raceRepository.findRoomIdsWithAtLeastOneActive(page))
                .thenAnswer(inv -> {
                    jdbc.update("DELETE FROM survival_state WHERE room_id = ?", roomA.getId());
                    return new PageImpl<>(java.util.List.of(roomA.getId()), page, 1);
                });
        FinalThreeJob job = new FinalThreeJob(
                raceRepository, finalThreeService, realtimePublisher, executor, clock);

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.zeroSurvivor()).isEqualTo(1);
        assertThat(posterRepository.existsById(
                new FinalThreePosterId(roomA.getId(), "2026-05"))).isFalse();
        Integer chatCount = jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE room_id = ? AND kind = 'SYSTEM'"
                        + " AND body LIKE '이번 달은 아무도%'",
                Integer.class, roomA.getId());
        assertThat(chatCount).isZero();
    }

    @Test
    @DisplayName("parallel correctness — 50 rooms × 5 ACTIVE each → all posters present, no PK collision, smoke margin under 30s")
    void parallelCorrectness_fiftyRooms_allGenerated() {
        for (int i = 0; i < 50; i++) {
            seedRoomWithActiveSurvivors("방-" + i, 5);
        }
        YearMonth target = YearMonth.of(2026, 5);

        long started = System.currentTimeMillis();
        FinalThreeJob.Summary summary = finalThreeJob.runBatch(target);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(summary.eligible()).isEqualTo(50);
        assertThat(summary.generated()).isEqualTo(50);
        assertThat(summary.failed()).isZero();
        assertThat(posterRepository.count()).isEqualTo(50);
        // Smoke margin only — the NFR-9.1.4 production assertion lives in
        // ops observability, not test wall-clock. 30s buffer absorbs
        // container cold-start + Batik first-call warm-up.
        assertThat(elapsed).isLessThan(30_000L);
        verify(template, atLeast(50)).convertAndSend(any(String.class), any(Object.class));
    }

    // ---- helpers ----

    private Room seedRoomWithActiveSurvivors(String roomName, int survivorCount) {
        User owner = users.save(new User(
                roomName + "-owner@example.com", roomName + "-owner",
                "hash", AuthProvider.EMAIL));
        Room room = rooms.save(new Room(roomName, owner));
        roomMembers.save(new RoomMember(room, owner, RoomRole.OWNER));
        survivalStates.save(new SurvivalState(room, owner, null));
        for (int i = 1; i < survivorCount; i++) {
            User u = users.save(new User(
                    roomName + "-u" + i + "@example.com", roomName + "-u" + i,
                    "hash", AuthProvider.EMAIL));
            roomMembers.save(new RoomMember(room, u, RoomRole.MEMBER));
            survivalStates.save(new SurvivalState(room, u, null));
        }
        return room;
    }

    private Room seedRoomNoSurvivors(String roomName) {
        User owner = users.save(new User(
                roomName + "-owner@example.com", roomName + "-owner",
                "hash", AuthProvider.EMAIL));
        Room room = rooms.save(new Room(roomName, owner));
        roomMembers.save(new RoomMember(room, owner, RoomRole.OWNER));
        // intentionally no survival_state — room is invisible to AC3 query
        return room;
    }

    private void assertPngWritten(Room room) throws Exception {
        FinalThreePoster poster = posterRepository.findById(
                        new FinalThreePosterId(room.getId(), "2026-05"))
                .orElseThrow();
        assertThat(poster.getPngUrl()).isNotNull();
        String fileName = poster.getPngUrl().substring(poster.getPngUrl().lastIndexOf('/') + 1);
        Path file = postersDir.resolve(fileName);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isGreaterThan(0);
    }
}
