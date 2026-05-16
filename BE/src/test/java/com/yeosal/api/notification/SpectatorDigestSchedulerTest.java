package com.yeosal.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.survival.SpectatorDigestService;
import com.yeosal.api.survival.SpectatorDigestService.DigestEntry;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Story 2.2 AC2 / AC5 / AC8 — unit coverage for the 09:00 KST spectator
 * digest scheduler. Exercises the paged user fan-out + dedup-key + title/body
 * composition. The pref toggle, quiet hours, and dedup gate live in
 * {@link NotificationService#sendCron} and are covered by
 * {@code NotificationServiceTest} + the integration test for this story.
 */
@ExtendWith(MockitoExtension.class)
class SpectatorDigestSchedulerTest {

    // 2026-05-15 03:14:15 UTC == 2026-05-15 12:14 KST → priorEntryDate = 2026-05-14
    private static final Instant NOW = Instant.parse("2026-05-15T03:14:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
    private static final LocalDate PRIOR = LocalDate.of(2026, 5, 14);

    @Mock private NotificationService notifications;
    @Mock private SpectatorDigestService digestService;
    @Mock private UserRepository users;

    private SpectatorDigestScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SpectatorDigestScheduler(notifications, digestService, users, CLOCK);
    }

    @Test
    @DisplayName("single user / single spectator-room with activity → one sendCron with correct dedup key + title/body")
    void runDailyDigest_singleUserSingleRoom_sendsOnePush() {
        User alice = makeUser(1L, "alice@example.com", "Alice");
        whenUsersFindAllPagedReturns(alice);
        when(digestService.evaluateForUser(1L, PRIOR))
                .thenReturn(List.of(new DigestEntry(42L, "팀 A", 12, 0, 3)));

        scheduler.runDailyDigest();

        ArgumentCaptor<String> dedupKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendCron(
                eq(alice),
                eq(NotificationKind.SPECTATOR_DIGEST),
                dedupKey.capture(),
                title.capture(),
                body.capture());

        assertThat(dedupKey.getValue()).isEqualTo("2026-05-14:1:42");
        assertThat(title.getValue()).isEqualTo("오늘도 팀 A 함께 살아남고 있어요");
        assertThat(body.getValue()).isEqualTo("어제 메시지 12개 · 새 글 3개");
    }

    @Test
    @DisplayName("zero-activity day → no sendCron call at all")
    void runDailyDigest_zeroActivity_noSends() {
        User alice = makeUser(1L, "alice@example.com", "Alice");
        whenUsersFindAllPagedReturns(alice);
        when(digestService.evaluateForUser(1L, PRIOR)).thenReturn(List.of());

        scheduler.runDailyDigest();

        verify(notifications, never()).sendCron(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("body falls back to 'new posts only' when chat count is 0 but daily entries > 0")
    void runDailyDigest_chatZeroDailyOnly_swapsBody() {
        User alice = makeUser(1L, "alice@example.com", "Alice");
        whenUsersFindAllPagedReturns(alice);
        when(digestService.evaluateForUser(1L, PRIOR))
                .thenReturn(List.of(new DigestEntry(42L, "팀 A", 0, 1, 4)));

        scheduler.runDailyDigest();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendCron(
                eq(alice), eq(NotificationKind.SPECTATOR_DIGEST),
                anyString(), anyString(), body.capture());
        assertThat(body.getValue()).isEqualTo("어제 새 글 4개");
    }

    @Test
    @DisplayName("body falls back to 'messages only' when daily entries are 0 but chat count > 0")
    void runDailyDigest_dailyZeroChatOnly_swapsBody() {
        User alice = makeUser(1L, "alice@example.com", "Alice");
        whenUsersFindAllPagedReturns(alice);
        when(digestService.evaluateForUser(1L, PRIOR))
                .thenReturn(List.of(new DigestEntry(42L, "팀 A", 7, 0, 0)));

        scheduler.runDailyDigest();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendCron(
                eq(alice), eq(NotificationKind.SPECTATOR_DIGEST),
                anyString(), anyString(), body.capture());
        assertThat(body.getValue()).isEqualTo("어제 메시지 7개");
    }

    @Test
    @DisplayName("state-only room renders generic warm copy (does not leak '메시지 0개 · 새 글 0개')")
    void runDailyDigest_stateOnlyActivity_rendersGenericBody() {
        // AC4 keeps state-only rooms in the digest list; AC5 forbids exposing
        // stateChangeCount in the body. The previous composeBody fell through
        // to "어제 메시지 0개 · 새 글 0개" which is the Story 2.2 review-#2 bug
        // — surfacing zero stats. Fix: a 3rd branch that emits a generic
        // warm-tone line when both chat and daily counts are zero.
        User alice = makeUser(1L, "alice@example.com", "Alice");
        whenUsersFindAllPagedReturns(alice);
        when(digestService.evaluateForUser(1L, PRIOR))
                .thenReturn(List.of(new DigestEntry(42L, "팀 A", 0, 2, 0)));

        scheduler.runDailyDigest();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendCron(
                eq(alice), eq(NotificationKind.SPECTATOR_DIGEST),
                anyString(), anyString(), body.capture());
        assertThat(body.getValue()).isEqualTo("어제 방에 작은 변화가 있었어요");
    }

    @Test
    @DisplayName("paging — 1500 users across 3 pages of 500 → 1500 evaluations attempted")
    void runDailyDigest_paginatesThreePages() {
        List<User> all = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            all.add(makeUser(i + 1L, "u" + i + "@example.com", "U" + i));
        }
        when(users.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            int from = (int) pageable.getOffset();
            int to = Math.min(from + pageable.getPageSize(), all.size());
            return new PageImpl<>(all.subList(from, to), pageable, all.size());
        });
        when(digestService.evaluateForUser(anyLong(), eq(PRIOR))).thenReturn(List.of());

        scheduler.runDailyDigest();

        verify(digestService, times(1500)).evaluateForUser(anyLong(), eq(PRIOR));
    }

    @Test
    @DisplayName("paging — one-user failure does NOT abort the loop")
    void runDailyDigest_oneUserFailureIsIsolated() {
        User a = makeUser(1L, "a@example.com", "A");
        User b = makeUser(2L, "b@example.com", "B");
        User c = makeUser(3L, "c@example.com", "C");
        whenUsersFindAllPagedReturns(a, b, c);
        when(digestService.evaluateForUser(1L, PRIOR)).thenReturn(List.of());
        when(digestService.evaluateForUser(2L, PRIOR))
                .thenThrow(new RuntimeException("boom"));
        when(digestService.evaluateForUser(3L, PRIOR))
                .thenReturn(List.of(new DigestEntry(99L, "팀 C", 1, 0, 0)));

        scheduler.runDailyDigest();

        // user 3 still processed despite user 2 throwing
        verify(notifications).sendCron(
                eq(c), eq(NotificationKind.SPECTATOR_DIGEST),
                eq("2026-05-14:3:99"), anyString(), anyString());
    }

    @Test
    @DisplayName("spectator in two rooms → two sendCron calls, one per room with distinct dedup keys")
    void runDailyDigest_twoRoomsTwoDistinctSends() {
        User alice = makeUser(1L, "alice@example.com", "Alice");
        whenUsersFindAllPagedReturns(alice);
        when(digestService.evaluateForUser(1L, PRIOR))
                .thenReturn(List.of(
                        new DigestEntry(42L, "팀 A", 5, 0, 0),
                        new DigestEntry(43L, "팀 B", 0, 0, 2)
                ));

        scheduler.runDailyDigest();

        verify(notifications).sendCron(
                eq(alice), eq(NotificationKind.SPECTATOR_DIGEST),
                eq("2026-05-14:1:42"), anyString(), anyString());
        verify(notifications).sendCron(
                eq(alice), eq(NotificationKind.SPECTATOR_DIGEST),
                eq("2026-05-14:1:43"), anyString(), anyString());
    }

    // -- helpers --

    private void whenUsersFindAllPagedReturns(User... entries) {
        List<User> list = List.of(entries);
        when(users.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            int from = (int) pageable.getOffset();
            if (from >= list.size()) {
                return new PageImpl<>(List.of(), pageable, list.size());
            }
            int to = Math.min(from + pageable.getPageSize(), list.size());
            return new PageImpl<>(list.subList(from, to), pageable, list.size());
        });
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
}
