package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.chat.ChatMessageRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 2.2 AC4 / AC7 — unit coverage for the spectator-digest aggregator.
 *
 * <p>Window contract: for {@code priorEntryDate = D}, the aggregator counts
 * activity inside {@code [D 06:00 KST, D+1 06:00 KST)} — the same boundary
 * Story 1.2's survival evaluator uses. The boundary tests verify the
 * service passes the exact instants to the repos, not the entire
 * boundary-resolution code path (the repos themselves are derived-query
 * stubs Mockito-mocked here; the integration test exercises the SQL).
 */
@ExtendWith(MockitoExtension.class)
class SpectatorDigestServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate PRIOR = LocalDate.of(2026, 5, 14);
    private static final Instant FROM = PRIOR.atStartOfDay(KST).plusHours(6).toInstant();
    private static final Instant TO = PRIOR.plusDays(1).atStartOfDay(KST).plusHours(6).toInstant();

    private static final long VIEWER_ID = 7L;

    @Mock private SurvivalStateRepository survivalRepo;
    @Mock private ChatMessageRepository chatRepo;
    @Mock private DailyEntryRepository dailyEntryRepo;

    private SpectatorDigestService service;
    private User viewer;

    @BeforeEach
    void setUp() {
        service = new SpectatorDigestService(survivalRepo, chatRepo, dailyEntryRepo);
        viewer = makeUser(VIEWER_ID, "viewer@example.com", "Viewer");
    }

    @Test
    @DisplayName("spectator in one active room with chat activity → returns single DigestEntry with counts")
    void evaluateForUser_singleSpectatorRoomWithChat_returnsOneEntry() {
        Room roomA = makeRoom(11L, "팀 A");
        SurvivalState spectator = stateOf(roomA, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(spectator));
        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(11L, FROM, TO)).thenReturn(12L);
        when(survivalRepo.countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(11L, FROM, TO)).thenReturn(0L);
        when(dailyEntryRepo.countByEntryDateAndRoomId(PRIOR, 11L)).thenReturn(3L);

        List<SpectatorDigestService.DigestEntry> result =
                service.evaluateForUser(VIEWER_ID, PRIOR);

        assertThat(result).singleElement().satisfies(entry -> {
            assertThat(entry.roomId()).isEqualTo(11L);
            assertThat(entry.roomName()).isEqualTo("팀 A");
            assertThat(entry.chatMessageCount()).isEqualTo(12);
            assertThat(entry.stateChangeCount()).isZero();
            assertThat(entry.dailyEntryCount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("spectator with zero activity across all counts → returns empty list")
    void evaluateForUser_zeroActivity_returnsEmpty() {
        Room roomA = makeRoom(11L, "팀 A");
        SurvivalState spectator = stateOf(roomA, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(spectator));
        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(11L, FROM, TO)).thenReturn(0L);
        when(survivalRepo.countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(11L, FROM, TO)).thenReturn(0L);
        when(dailyEntryRepo.countByEntryDateAndRoomId(PRIOR, 11L)).thenReturn(0L);

        List<SpectatorDigestService.DigestEntry> result =
                service.evaluateForUser(VIEWER_ID, PRIOR);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE (non-spectator) room is filtered out — no count queries fire for it")
    void evaluateForUser_nonSpectatorRoomExcluded() {
        Room roomA = makeRoom(11L, "팀 A");
        SurvivalState active = stateOf(roomA, viewer, SurvivalStatus.ACTIVE);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(active));

        List<SpectatorDigestService.DigestEntry> result =
                service.evaluateForUser(VIEWER_ID, PRIOR);

        assertThat(result).isEmpty();
        verify(chatRepo, never()).countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(anyLong(), any(), any());
        verify(survivalRepo, never()).countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(anyLong(), any(), any());
        verify(dailyEntryRepo, never()).countByEntryDateAndRoomId(any(), anyLong());
    }

    @Test
    @DisplayName("YELLOW / RED states are excluded — digest is SPECTATOR-only (AC7)")
    void evaluateForUser_yellowAndRedExcluded() {
        Room roomA = makeRoom(11L, "팀 A");
        Room roomB = makeRoom(12L, "팀 B");
        SurvivalState yellow = stateOf(roomA, viewer, SurvivalStatus.YELLOW);
        SurvivalState red = stateOf(roomB, viewer, SurvivalStatus.RED);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(yellow, red));

        List<SpectatorDigestService.DigestEntry> result =
                service.evaluateForUser(VIEWER_ID, PRIOR);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("spectator in two rooms — one active, one quiet → returns single entry")
    void evaluateForUser_twoRoomsOneQuiet_returnsOneEntry() {
        Room roomA = makeRoom(11L, "팀 A");
        Room roomB = makeRoom(12L, "팀 B");
        SurvivalState specA = stateOf(roomA, viewer, SurvivalStatus.SPECTATOR);
        SurvivalState specB = stateOf(roomB, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(specA, specB));

        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(11L, FROM, TO)).thenReturn(5L);
        when(survivalRepo.countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(11L, FROM, TO)).thenReturn(0L);
        when(dailyEntryRepo.countByEntryDateAndRoomId(PRIOR, 11L)).thenReturn(0L);

        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(12L, FROM, TO)).thenReturn(0L);
        when(survivalRepo.countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(12L, FROM, TO)).thenReturn(0L);
        when(dailyEntryRepo.countByEntryDateAndRoomId(PRIOR, 12L)).thenReturn(0L);

        List<SpectatorDigestService.DigestEntry> result =
                service.evaluateForUser(VIEWER_ID, PRIOR);

        assertThat(result).singleElement().satisfies(e -> {
            assertThat(e.roomId()).isEqualTo(11L);
            assertThat(e.chatMessageCount()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("spectator in two active rooms → returns two entries")
    void evaluateForUser_twoActiveRooms_returnsTwoEntries() {
        Room roomA = makeRoom(11L, "팀 A");
        Room roomB = makeRoom(12L, "팀 B");
        SurvivalState specA = stateOf(roomA, viewer, SurvivalStatus.SPECTATOR);
        SurvivalState specB = stateOf(roomB, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(specA, specB));

        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(11L, FROM, TO)).thenReturn(7L);
        when(survivalRepo.countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(11L, FROM, TO)).thenReturn(1L);
        when(dailyEntryRepo.countByEntryDateAndRoomId(PRIOR, 11L)).thenReturn(2L);

        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(12L, FROM, TO)).thenReturn(0L);
        when(survivalRepo.countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(12L, FROM, TO)).thenReturn(0L);
        when(dailyEntryRepo.countByEntryDateAndRoomId(PRIOR, 12L)).thenReturn(1L);

        List<SpectatorDigestService.DigestEntry> result =
                service.evaluateForUser(VIEWER_ID, PRIOR);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(SpectatorDigestService.DigestEntry::roomId)
                .containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    @DisplayName("day-boundary window is exactly [priorDate 06:00 KST, priorDate+1 06:00 KST) — passed to repos")
    void evaluateForUser_dayBoundaryInstantsArePassedToRepos() {
        Room roomA = makeRoom(11L, "팀 A");
        SurvivalState spectator = stateOf(roomA, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(spectator));
        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(eq(11L), any(), any())).thenReturn(1L);

        service.evaluateForUser(VIEWER_ID, PRIOR);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(chatRepo).countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(11L), fromCaptor.capture(), toCaptor.capture());

        // 2026-05-14 06:00 KST == 2026-05-13 21:00 UTC
        assertThat(fromCaptor.getValue()).isEqualTo(Instant.parse("2026-05-13T21:00:00Z"));
        // 2026-05-15 06:00 KST == 2026-05-14 21:00 UTC
        assertThat(toCaptor.getValue()).isEqualTo(Instant.parse("2026-05-14T21:00:00Z"));
    }

    @Test
    @DisplayName("repo contract — service calls the explicit half-open derived-query methods (not the inclusive Between)")
    void evaluateForUser_callsHalfOpenDerivedQueryMethods() {
        // This test guards against a regression that the count methods get
        // accidentally renamed back to `Between` (which Spring Data treats as
        // INCLUSIVE on both ends, double-counting boundary instants across
        // adjacent digest runs — Story 2.2 review finding #1).
        Room roomA = makeRoom(11L, "팀 A");
        SurvivalState spectator = stateOf(roomA, viewer, SurvivalStatus.SPECTATOR);
        when(survivalRepo.findByUserIdFetchingRoom(VIEWER_ID)).thenReturn(List.of(spectator));
        when(chatRepo.countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                11L, FROM, TO)).thenReturn(1L);

        service.evaluateForUser(VIEWER_ID, PRIOR);

        verify(chatRepo).countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(11L, FROM, TO);
        verify(survivalRepo).countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(
                11L, FROM, TO);
    }

    // -- helpers --

    private static Room makeRoom(long id, String name) {
        Room r = new Room(name, null);
        setField(r, "id", id);
        return r;
    }

    private static SurvivalState stateOf(Room room, User user, SurvivalStatus status) {
        SurvivalState s = new SurvivalState(room, user, null);
        s.setStatus(status);
        return s;
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return u;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
