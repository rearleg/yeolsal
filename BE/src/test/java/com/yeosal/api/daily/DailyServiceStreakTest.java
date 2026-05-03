package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.yeosal.api.notification.NotificationService;
import com.yeosal.api.profile.GrassDay;
import com.yeosal.api.room.RoomMemberRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DailyServiceStreakTest {

    private DailyService service;

    @BeforeEach
    void setUp() {
        service = new DailyService(
                mock(DailyEntryRepository.class),
                mock(TodoItemRepository.class),
                mock(ReflectionRepository.class),
                mock(EntryDateResolver.class),
                new GateRule(),
                Clock.systemUTC(),
                mock(RoomMemberRepository.class),
                mock(NotificationService.class),
                mock(com.yeosal.api.room.chat.ChatService.class)
        );
    }

    private GrassDay day(LocalDate d, int intensity) {
        return new GrassDay(d, intensity > 0, intensity, intensity > 0, intensity);
    }

    @Test
    @DisplayName("streak: empty window returns zero")
    void empty_window_returns_zero() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        assertThat(service.streakFromGrass(today, List.of())).isZero();
    }

    @Test
    @DisplayName("streak: counts consecutive gate-passing days back from today")
    void counts_back_from_today() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        List<GrassDay> window = List.of(
                day(today.minusDays(4), 0),
                day(today.minusDays(3), 1),
                day(today.minusDays(2), 2),
                day(today.minusDays(1), 1),
                day(today,                3)
        );
        assertThat(service.streakFromGrass(today, window)).isEqualTo(4);
    }

    @Test
    @DisplayName("streak: today not yet recorded does not break the run")
    void today_unrecorded_keeps_streak() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        List<GrassDay> window = List.of(
                day(today.minusDays(2), 1),
                day(today.minusDays(1), 1),
                day(today,                0) // today empty (intensity 0)
        );
        assertThat(service.streakFromGrass(today, window)).isEqualTo(2);
    }

    @Test
    @DisplayName("streak: a zero day mid-window terminates the streak")
    void mid_zero_terminates() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        List<GrassDay> window = new ArrayList<>(List.of(
                day(today.minusDays(4), 1),
                day(today.minusDays(3), 0),  // breaks streak
                day(today.minusDays(2), 1),
                day(today.minusDays(1), 1),
                day(today,                1)
        ));
        assertThat(service.streakFromGrass(today, window)).isEqualTo(3);
    }
}
