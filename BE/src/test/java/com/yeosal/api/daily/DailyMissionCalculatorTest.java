package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DailyMissionCalculatorTest {
    private final DailyMissionCalculator calculator = new DailyMissionCalculator(ZoneId.of("Asia/Seoul"));

    @Test
    void entryOnlyIsIncomplete() {
        DailyMissionStatus status = calculator.calculate(LocalDate.of(2026, 4, 26), true, null);

        assertThat(status.missionCompleted()).isFalse();
    }

    @Test
    void reflectionBeforeSixAmNextDayCompletesMission() {
        DailyMissionStatus status = calculator.calculate(
                LocalDate.of(2026, 4, 26),
                true,
                ZonedDateTime.of(2026, 4, 27, 5, 59, 0, 0, ZoneId.of("Asia/Seoul"))
        );

        assertThat(status.missionCompleted()).isTrue();
    }

    @Test
    void reflectionAtSixAmIsLate() {
        DailyMissionStatus status = calculator.calculate(
                LocalDate.of(2026, 4, 26),
                true,
                ZonedDateTime.of(2026, 4, 27, 6, 0, 0, 0, ZoneId.of("Asia/Seoul"))
        );

        assertThat(status.missionCompleted()).isFalse();
    }

    @Test
    void monthBoundaryUsesEntryDateNextMorning() {
        DailyMissionStatus status = calculator.calculate(
                LocalDate.of(2026, 4, 30),
                true,
                ZonedDateTime.of(2026, 5, 1, 5, 30, 0, 0, ZoneId.of("Asia/Seoul"))
        );

        assertThat(status.missionCompleted()).isTrue();
    }
}
