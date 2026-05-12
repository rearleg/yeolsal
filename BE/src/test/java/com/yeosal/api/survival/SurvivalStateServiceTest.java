package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.notification.NotificationLogRepository;
import com.yeosal.api.revival.PersonalPointsLedgerRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class SurvivalStateServiceTest {

    @Mock private SurvivalStateRepository repository;
    @Mock private NotificationLogRepository notificationLogs;
    @Mock private UserRepository users;
    @Mock private StreakFreezeRepository streakFreezes;
    @Mock private PersonalPointsLedgerRepository personalLedger;
    @Mock private DailyEntryRepository dailyEntries;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomRepository rooms;
    @Mock private RoomRuleVersionRepository ruleVersions;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-11T03:14:15Z"), ZoneId.of("UTC"));

    private SurvivalStateService service;
    private Room room;
    private User user;

    @BeforeEach
    void setUp() {
        service = new SurvivalStateService(
                repository,
                notificationLogs,
                users,
                streakFreezes,
                personalLedger,
                dailyEntries,
                roomMembers,
                rooms,
                ruleVersions,
                eventPublisher,
                clock);
        user = makeUser(1L, "alice@example.com", "Alice");
        room = makeRoom(42L, "방", user);
    }

    @Test
    @DisplayName("initializeOnJoin: native upsert with grace_ends_at = joinedAt + 14d, then re-reads ACTIVE row")
    void initializeOnJoinInsertsAndReturnsActiveRow() {
        Instant joinedAt = Instant.parse("2026-05-11T03:14:15Z");
        Instant graceEndsAt = joinedAt.plus(Duration.ofDays(14));
        SurvivalState inserted = new SurvivalState(room, user, graceEndsAt);
        setId(inserted, 7L);
        when(repository.insertIfAbsent(42L, 1L, graceEndsAt)).thenReturn(1);
        when(repository.findByRoomIdAndUserId(42L, 1L)).thenReturn(Optional.of(inserted));

        SurvivalState result = service.initializeOnJoin(room, user, joinedAt);

        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo(SurvivalStatus.ACTIVE);
        assertThat(result.getRoom()).isSameAs(room);
        assertThat(result.getUser()).isSameAs(user);
        assertThat(result.getGraceEndsAt()).isEqualTo(graceEndsAt);
        verify(repository, times(1)).insertIfAbsent(42L, 1L, graceEndsAt);
        verify(repository, times(1)).findByRoomIdAndUserId(42L, 1L);
    }

    @Test
    @DisplayName("initializeOnJoin: idempotent under unique-(room,user) race — re-reads existing row when ON CONFLICT short-circuits")
    void initializeOnJoinIsIdempotentUnderUniqueRace() {
        Instant joinedAt = Instant.parse("2026-05-11T03:14:15Z");
        Instant graceEndsAt = joinedAt.plus(Duration.ofDays(14));
        SurvivalState existing = new SurvivalState(room, user, graceEndsAt);
        setId(existing, 99L);
        // 0 inserted rows = the unique-(room_id, user_id) constraint short-circuited;
        // the winning row already lives in the table.
        when(repository.insertIfAbsent(42L, 1L, graceEndsAt)).thenReturn(0);
        when(repository.findByRoomIdAndUserId(42L, 1L)).thenReturn(Optional.of(existing));

        SurvivalState result = service.initializeOnJoin(room, user, joinedAt);

        assertThat(result.getId()).isEqualTo(99L);
        verify(repository, times(1)).insertIfAbsent(42L, 1L, graceEndsAt);
        verify(repository, times(1)).findByRoomIdAndUserId(42L, 1L);
    }

    @Test
    @DisplayName("initializeOnJoin: throws IllegalStateException if the re-read returns empty (shouldn't happen, defensive)")
    void initializeOnJoinThrowsWhenReadMissesPostInsert() {
        Instant joinedAt = Instant.parse("2026-05-11T03:14:15Z");
        when(repository.insertIfAbsent(42L, 1L, joinedAt.plus(Duration.ofDays(14)))).thenReturn(1);
        when(repository.findByRoomIdAndUserId(42L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initializeOnJoin(room, user, joinedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("survival_state row missing after upsert");
    }

    @Test
    @DisplayName("inGraceWindow: true strictly before grace_ends_at; false at boundary and after")
    void inGraceWindowBoundaryAndAfter() {
        Instant joinedAt = Instant.parse("2026-05-11T03:14:15Z");
        SurvivalState s = new SurvivalState(room, user, joinedAt.plus(Duration.ofDays(14)));

        assertThat(service.inGraceWindow(s, joinedAt)).isTrue();
        assertThat(service.inGraceWindow(s, joinedAt.plus(Duration.ofDays(13)))).isTrue();
        // grace_ends_at is exclusive — once now == grace_ends_at, member is fully exposed.
        assertThat(service.inGraceWindow(s, joinedAt.plus(Duration.ofDays(14)))).isFalse();
        assertThat(service.inGraceWindow(s, joinedAt.plus(Duration.ofDays(15)))).isFalse();
    }

    @Test
    @DisplayName("inGraceWindow: legacy rows with null grace_ends_at are NOT in grace")
    void inGraceWindowNullGraceMeansNotInGrace() {
        SurvivalState legacy = new SurvivalState(room, user, null);

        assertThat(service.inGraceWindow(legacy, Instant.parse("2026-05-11T03:14:15Z"))).isFalse();
    }

    @Test
    @DisplayName("inGraceWindow guard contract (AC3/AC4): post-grace member is RED-eligible for Story 1.2")
    void inGraceWindowContractAssertionForStory12() {
        Instant now = Instant.parse("2026-05-11T03:14:15Z");
        SurvivalState postGrace = new SurvivalState(room, user, now.minus(Duration.ofDays(1)));

        assertThat(service.inGraceWindow(postGrace, now)).isFalse();
    }

    // -- helpers --
    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setId(u, id);
        return u;
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner);
        setId(r, id);
        return r;
    }

    private static <T> T setId(T entity, long id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return entity;
    }
}
