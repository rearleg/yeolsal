package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link SurvivalStateService#mySurvivalAcrossRooms(long)}
 * (Epic 1 retro T4, Architecture §6.4).
 *
 * <p>Exercises the cross-room aggregation: returns one DTO per
 * {@link SurvivalState} row keyed by {@code user_id}, with {@code roomId},
 * {@code roomName}, and {@code status} pulled through the fetch-join.
 * Empty for a user with no memberships.
 */
@ExtendWith(MockitoExtension.class)
class SurvivalStateServiceMeAcrossRoomsTest {

    private static final Instant NOW = Instant.parse("2026-05-15T03:14:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private static final long VIEWER_ID = 7L;

    @Mock private SurvivalStateRepository survivalRepo;
    @Mock private NotificationLogRepository notificationLogs;
    @Mock private UserRepository users;
    @Mock private StreakFreezeRepository streakFreezes;
    @Mock private PersonalPointsLedgerRepository personalLedger;
    @Mock private DailyEntryRepository dailyEntries;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomRepository rooms;
    @Mock private RoomRuleVersionRepository ruleVersions;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SurvivalStateService service;
    private User viewer;

    @BeforeEach
    void setUp() {
        service = new SurvivalStateService(
                survivalRepo, notificationLogs, users, streakFreezes, personalLedger,
                dailyEntries, roomMembers, rooms, ruleVersions, eventPublisher, CLOCK);
        viewer = makeUser(VIEWER_ID, "viewer@example.com", "Viewer");
    }

    @Test
    @DisplayName("maps each SurvivalState row to a MeSurvivalEntryDto with roomId/roomName/status")
    void mySurvivalAcrossRooms_mapsEveryRowWithRoomAndStatus() {
        Room roomA = makeRoom(11L, "팀 A", viewer);
        Room roomB = makeRoom(12L, "팀 B", viewer);
        SurvivalState activeRow = stateOf(roomA, viewer, SurvivalStatus.ACTIVE);
        SurvivalState spectatorRow = stateOf(roomB, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID))
                .thenReturn(List.of(activeRow, spectatorRow));

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        assertThat(result).containsExactly(
                new MeSurvivalEntryDto(11L, "팀 A", SurvivalStatus.ACTIVE),
                new MeSurvivalEntryDto(12L, "팀 B", SurvivalStatus.SPECTATOR));
    }

    @Test
    @DisplayName("returns an empty list when the user has no memberships (no NotFoundException)")
    void mySurvivalAcrossRooms_noMemberships_returnsEmptyList() {
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of());

        assertThat(service.mySurvivalAcrossRooms(VIEWER_ID)).isEmpty();
    }

    @Test
    @DisplayName("preserves repository ordering and never collapses duplicate room ids")
    void mySurvivalAcrossRooms_preservesRepositoryOrdering() {
        Room first = makeRoom(31L, "방 하나", viewer);
        Room second = makeRoom(32L, "방 둘", viewer);
        Room third = makeRoom(33L, "방 셋", viewer);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(
                stateOf(third, viewer, SurvivalStatus.RED),
                stateOf(first, viewer, SurvivalStatus.ACTIVE),
                stateOf(second, viewer, SurvivalStatus.YELLOW)));

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        assertThat(result).extracting(MeSurvivalEntryDto::roomId)
                .containsExactly(33L, 31L, 32L);
        assertThat(result).extracting(MeSurvivalEntryDto::status)
                .containsExactly(SurvivalStatus.RED, SurvivalStatus.ACTIVE, SurvivalStatus.YELLOW);
    }

    // ----- helpers -----

    private static SurvivalState stateOf(Room room, User user, SurvivalStatus status) {
        SurvivalState s = new SurvivalState(room, user, /* graceEndsAt */ null);
        try {
            Field f = SurvivalState.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(s, status);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        return setId(u, id);
    }

    private static Room makeRoom(long id, String name, User owner) {
        Room r = new Room(name, owner);
        return setId(r, id);
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
