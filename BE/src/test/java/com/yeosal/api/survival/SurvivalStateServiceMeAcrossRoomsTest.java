package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * (Epic 1 retro T4 — Architecture §6.4; Story 2.1 AC7 — WalletPreview fields).
 *
 * <p>Exercises the cross-room aggregation: returns one DTO per
 * {@link SurvivalState} row keyed by {@code user_id}, with {@code roomId},
 * {@code roomName}, {@code status}, {@code personalPoints}, and
 * {@code roomPointPool} pulled through the fetch-join + per-row SUM.
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
    @DisplayName("maps each SurvivalState row to a MeSurvivalEntryDto with all 5 wire fields (Story 2.1 AC7)")
    void mySurvivalAcrossRooms_mapsEveryRowWithWalletFields() {
        Room roomA = makeRoom(11L, "팀 A", viewer);
        Room roomB = makeRoom(12L, "팀 B", viewer);
        SurvivalState activeRow = stateOf(roomA, viewer, SurvivalStatus.ACTIVE);
        SurvivalState spectatorRow = stateOf(roomB, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID))
                .thenReturn(List.of(activeRow, spectatorRow));
        when(personalLedger.sumDeltaByUserIdAndRoomId(VIEWER_ID, 11L)).thenReturn(8);
        when(personalLedger.sumDeltaByUserIdAndRoomId(VIEWER_ID, 12L)).thenReturn(-2);
        when(survivalRepo.findRoomPointPoolTotal(11L)).thenReturn(15);
        when(survivalRepo.findRoomPointPoolTotal(12L)).thenReturn(0);

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        // freeRevivalTicketUsed is false by default — viewer hasn't been
        // stubbed on `users.findById(...)` so Mockito returns Optional.empty()
        // and the service path coalesces to false (matches User entity
        // default for a fresh account).
        assertThat(result).containsExactly(
                new MeSurvivalEntryDto(11L, "팀 A", SurvivalStatus.ACTIVE, 8, 15, false),
                new MeSurvivalEntryDto(12L, "팀 B", SurvivalStatus.SPECTATOR, -2, 0, false));
    }

    @Test
    @DisplayName("personalPoints coalesces to 0 when the ledger SUM returns null (no rows for that pair)")
    void mySurvivalAcrossRooms_nullLedgerSum_coalescesToZero() {
        Room roomA = makeRoom(31L, "신규 방", viewer);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID))
                .thenReturn(List.of(stateOf(roomA, viewer, SurvivalStatus.ACTIVE)));
        // Mockito default for an unstubbed Integer is null — the service
        // MUST treat that as 0 rather than NPE.
        when(personalLedger.sumDeltaByUserIdAndRoomId(VIEWER_ID, 31L)).thenReturn(null);

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        assertThat(result).hasSize(1);
        MeSurvivalEntryDto only = result.get(0);
        assertThat(only.personalPoints()).isZero();
        assertThat(only.roomPointPool()).isZero();
    }

    @Test
    @DisplayName("roomPointPool coalesces to 0 when the pool row is missing (defensive read)")
    void mySurvivalAcrossRooms_missingPoolRow_coalescesToZero() {
        Room roomA = makeRoom(41L, "포인트 방", viewer);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID))
                .thenReturn(List.of(stateOf(roomA, viewer, SurvivalStatus.SPECTATOR)));
        when(personalLedger.sumDeltaByUserIdAndRoomId(anyLong(), anyLong())).thenReturn(42);
        // Unstubbed survivalRepo.findRoomPointPoolTotal(...) → null → coalesce to 0.

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).roomPointPool()).isZero();
        assertThat(result.get(0).personalPoints()).isEqualTo(42);
    }

    @Test
    @DisplayName("returns an empty list when the user has no memberships (no NotFoundException)")
    void mySurvivalAcrossRooms_noMemberships_returnsEmptyList() {
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of());

        assertThat(service.mySurvivalAcrossRooms(VIEWER_ID)).isEmpty();
    }

    @Test
    @DisplayName("Story 3.1 — freeRevivalTicketUsed reflects users.free_revival_ticket_used; replicated per row")
    void mySurvivalAcrossRooms_freeTicketUsed_replicatedPerRow() {
        Room roomA = makeRoom(51L, "팀 A", viewer);
        Room roomB = makeRoom(52L, "팀 B", viewer);
        // Flip the viewer's flag to true to assert the read path.
        try {
            Field f = User.class.getDeclaredField("freeRevivalTicketUsed");
            f.setAccessible(true);
            f.setBoolean(viewer, true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        when(users.findById(VIEWER_ID)).thenReturn(java.util.Optional.of(viewer));
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(
                stateOf(roomA, viewer, SurvivalStatus.SPECTATOR),
                stateOf(roomB, viewer, SurvivalStatus.ACTIVE)));
        when(personalLedger.sumDeltaByUserIdAndRoomId(anyLong(), anyLong())).thenReturn(0);

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        assertThat(result).extracting(MeSurvivalEntryDto::freeRevivalTicketUsed)
                .containsExactly(true, true);
    }

    @Test
    @DisplayName("Story 3.1 — fresh user (no User row resolved) defaults freeRevivalTicketUsed to false")
    void mySurvivalAcrossRooms_freshUser_defaultsFreeTicketUsedFalse() {
        Room roomA = makeRoom(61L, "방 새내기", viewer);
        when(users.findById(VIEWER_ID)).thenReturn(java.util.Optional.empty());
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID))
                .thenReturn(List.of(stateOf(roomA, viewer, SurvivalStatus.ACTIVE)));
        when(personalLedger.sumDeltaByUserIdAndRoomId(VIEWER_ID, 61L)).thenReturn(0);

        List<MeSurvivalEntryDto> result = service.mySurvivalAcrossRooms(VIEWER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).freeRevivalTicketUsed()).isFalse();
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
        when(personalLedger.sumDeltaByUserIdAndRoomId(anyLong(), anyLong())).thenReturn(0);

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
