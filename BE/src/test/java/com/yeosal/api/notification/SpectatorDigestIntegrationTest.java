package com.yeosal.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.daily.DailyEntry;
import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMember;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomRole;
import com.yeosal.api.room.chat.ChatMessage;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatMessageRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStateService;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 2.2 AC10 — full-pipeline integration test for the spectator daily
 * digest. Opt-in via {@code -Dyeosal.boot-smoke=true} to match the
 * project-wide IT convention (Testcontainers Postgres on demand, no H2).
 *
 * <p>Scenarios:
 * <ul>
 *   <li>happy path — spectator with chat + daily-entry activity gets exactly
 *       one push with the locked title / body / dedup-key shape.</li>
 *   <li>idempotency — second cron run produces no additional push and no
 *       extra {@code notification_log} row.</li>
 *   <li>pref toggle — {@code event_hooks_enabled = false} suppresses the
 *       push entirely.</li>
 *   <li>quiet hours — when the cron runs inside the user's quiet window
 *       no push is dispatched and no dedup row is written.</li>
 *   <li>ACTIVE user — confirmed not to receive the digest for any room.</li>
 * </ul>
 *
 * <p>Push delivery is mocked via {@link ExpoPushClient} so the test stays
 * hermetic; everything else (Flyway, JPA, transaction boundaries, the
 * full {@link NotificationService#sendCron} gate chain) runs against the
 * real PostgreSQL container.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
// Story 2.2 review finding #3: the class-scoped {@code PostgreSQLContainer} is
// shared across all five @Test methods. Without per-method rollback the seeds
// collide on {@code users.email} unique constraint and {@code notification_log}
// rows accumulate, breaking the idempotency-count assertion. {@code @Transactional}
// at class level wraps each test in a transaction that Spring Test rolls back
// by default — the {@code sendCron} call inside the scheduler uses
// {@code Propagation.REQUIRED} so it joins this outer transaction (the
// {@code logs.save} write is visible to in-test assertions, then discarded).
@Transactional
class SpectatorDigestIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-05-15 09:30 KST → priorEntryDate = 2026-05-14
    private static final Instant CRON_FIRING = LocalDate.of(2026, 5, 15)
            .atStartOfDay(KST).plusHours(9).plusMinutes(30).toInstant();
    private static final LocalDate PRIOR_DATE = LocalDate.of(2026, 5, 14);

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
    @Autowired private ChatMessageRepository chatMessages;
    @Autowired private SurvivalStateRepository survivalStates;
    @Autowired private SurvivalStateService survivalStateService;
    @Autowired private NotificationPrefRepository prefs;
    @Autowired private PushTokenRepository pushTokens;
    @Autowired private NotificationLogRepository notificationLogs;
    @Autowired private SpectatorDigestScheduler scheduler;
    @Autowired private TransactionTemplate tx;
    @MockBean private ExpoPushClient pushClient;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(CRON_FIRING, ZoneId.of("UTC"));
        }
    }

    private User alice;
    private User bob;
    private Room room;

    @BeforeEach
    void seed() {
        when(pushClient.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(true);

        alice = users.save(new User("alice-sdit@example.com", "Alice", "h", AuthProvider.EMAIL));
        bob = users.save(new User("bob-sdit@example.com", "Bob", "h", AuthProvider.EMAIL));
        room = rooms.save(new Room("survival room", alice));

        Instant joinedAt = Instant.parse("2026-04-01T00:00:00Z");
        seedMember(room, alice, RoomRole.OWNER, joinedAt);
        seedMember(room, bob, RoomRole.MEMBER, joinedAt);
        survivalStateService.initializeOnJoin(room, alice, joinedAt);
        survivalStateService.initializeOnJoin(room, bob, joinedAt);

        // bob is the spectator — the only user who should receive a digest.
        forceStatus(bob, room, SurvivalStatus.SPECTATOR);

        // push token so dispatch reaches the (mocked) ExpoPushClient
        pushTokens.save(new PushToken(bob, "ExponentPushToken[bob-test]", "ios"));

        // 3 chat messages by alice yesterday (KST 08:00, 12:00, 22:00)
        seedChat(room.getId(), alice.getId(),
                PRIOR_DATE.atStartOfDay(KST).plusHours(8).toInstant());
        seedChat(room.getId(), alice.getId(),
                PRIOR_DATE.atStartOfDay(KST).plusHours(12).toInstant());
        seedChat(room.getId(), alice.getId(),
                PRIOR_DATE.atStartOfDay(KST).plusHours(22).toInstant());

        // 1 daily entry by alice on priorDate
        dailyEntries.save(new DailyEntry(alice, PRIOR_DATE, "오늘의 목표"));
    }

    @Test
    @DisplayName("happy path — one push fires with locked title + body + dedup key")
    void runDailyDigest_happyPath_pushesOnceWithLockedShape() {
        scheduler.runDailyDigest();

        String expectedDedup = PRIOR_DATE + ":" + bob.getId() + ":" + room.getId();
        verify(pushClient, times(1)).send(
                eq(java.util.List.of("ExponentPushToken[bob-test]")),
                eq("오늘도 survival room 함께 살아남고 있어요"),
                eq("어제 메시지 3개 · 새 글 1개"),
                anyMap());
        assertThat(notificationLogs.existsByUserAndKindAndKey(
                bob, NotificationKind.SPECTATOR_DIGEST, expectedDedup)).isTrue();
    }

    @Test
    @DisplayName("idempotency — re-running the cron does NOT fire a second push or write a second log")
    void runDailyDigest_idempotent_secondRunIsNoOp() {
        scheduler.runDailyDigest();
        long logCountAfterFirst = notificationLogs.count();
        reset(pushClient);
        when(pushClient.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(true);

        scheduler.runDailyDigest();

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
        assertThat(notificationLogs.count()).isEqualTo(logCountAfterFirst);
    }

    @Test
    @DisplayName("pref toggle — event_hooks_enabled = false suppresses the push")
    void runDailyDigest_eventHooksDisabled_suppressesPush() {
        tx.executeWithoutResult(t -> {
            NotificationPref pref = prefs.findById(bob.getId()).orElseGet(() -> {
                prefs.insertDefaultIfAbsent(bob.getId());
                return prefs.findById(bob.getId()).orElseThrow();
            });
            pref.setEventHooksEnabled(false);
            prefs.save(pref);
        });

        scheduler.runDailyDigest();

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
        assertThat(notificationLogs.count()).isZero();
    }

    @Test
    @DisplayName("quiet hours — cron firing inside the quiet window suppresses push + dedup write")
    void runDailyDigest_quietHours_suppressesPushAndLog() {
        // Clock is fixed at 09:30 KST. Set bob's quiet window to 08:00→10:00
        // so the digest evaluation happens inside quiet hours.
        tx.executeWithoutResult(t -> {
            NotificationPref pref = prefs.findById(bob.getId()).orElseGet(() -> {
                prefs.insertDefaultIfAbsent(bob.getId());
                return prefs.findById(bob.getId()).orElseThrow();
            });
            pref.setQuietStartHour((short) 8);
            pref.setQuietEndHour((short) 10);
            prefs.save(pref);
        });

        scheduler.runDailyDigest();

        verify(pushClient, never()).send(anyList(), anyString(), anyString(), anyMap());
        assertThat(notificationLogs.count()).isZero();
    }

    @Test
    @DisplayName("ACTIVE user — not in SPECTATOR status → no digest")
    void runDailyDigest_activeUser_excluded() {
        // alice is OWNER and ACTIVE by initializeOnJoin defaults. Give her a
        // token and verify she still gets no push (only bob the spectator does).
        pushTokens.save(new PushToken(alice, "ExponentPushToken[alice-test]", "ios"));

        scheduler.runDailyDigest();

        verify(pushClient, times(1)).send(
                eq(java.util.List.of("ExponentPushToken[bob-test]")),
                anyString(), anyString(), anyMap());
        verify(pushClient, never()).send(
                eq(java.util.List.of("ExponentPushToken[alice-test]")),
                anyString(), anyString(), anyMap());
    }

    // -- helpers --

    private void seedMember(Room room, User user, RoomRole role, Instant joinedAt) {
        RoomMember rm = new RoomMember(room, user, role);
        rm.setJoinedAt(joinedAt);
        roomMembers.save(rm);
    }

    private void seedChat(long roomId, long senderUserId, Instant createdAt) {
        ChatMessage msg = new ChatMessage(roomId, senderUserId, ChatMessageKind.USER, "메시지");
        setField(msg, "createdAt", createdAt);
        chatMessages.save(msg);
    }

    private void forceStatus(User user, Room room, SurvivalStatus status) {
        tx.executeWithoutResult(t -> {
            SurvivalState state = survivalStates
                    .findByRoomIdAndUserId(room.getId(), user.getId())
                    .orElseThrow();
            // Package-private setters live in com.yeosal.api.survival — this
            // test sits in com.yeosal.api.notification (the spec's canonical
            // home for scheduler ITs), so we reach in through reflection
            // exactly the way SurvivalStateEvaluatorIT.forceSurvivalStateInDb
            // does for the same package-isolation reason.
            setField(state, "status", status);
            setField(state, "lastStateChangeAt", Instant.now());
            survivalStates.save(state);
        });
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
        }
        throw new IllegalArgumentException("no field '" + name + "' on " + target.getClass());
    }
}
