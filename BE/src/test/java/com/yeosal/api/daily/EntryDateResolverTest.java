package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class EntryDateResolverTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final EntryDateResolver resolver = new EntryDateResolver();

    @Test
    void timestampAfterSixAmReturnsSameDay() {
        Instant ts = ZonedDateTime.of(2026, 4, 29, 6, 0, 0, 0, KST).toInstant();

        assertThat(resolver.resolve(ts, KST)).isEqualTo(LocalDate.of(2026, 4, 29));
    }

    @Test
    void timestampJustBeforeSixAmReturnsPreviousDay() {
        Instant ts = ZonedDateTime.of(2026, 4, 29, 5, 59, 59, 0, KST).toInstant();

        assertThat(resolver.resolve(ts, KST)).isEqualTo(LocalDate.of(2026, 4, 28));
    }

    @Test
    void timestampAtMidnightReturnsPreviousDay() {
        Instant ts = ZonedDateTime.of(2026, 4, 29, 0, 0, 0, 0, KST).toInstant();

        assertThat(resolver.resolve(ts, KST)).isEqualTo(LocalDate.of(2026, 4, 28));
    }

    @Test
    void timestampLateAtNightReturnsSameDay() {
        Instant ts = ZonedDateTime.of(2026, 4, 29, 23, 30, 0, 0, KST).toInstant();

        assertThat(resolver.resolve(ts, KST)).isEqualTo(LocalDate.of(2026, 4, 29));
    }

    @Test
    void timestampInDifferentZoneUsesUserZone() {
        // 2026-04-29 02:00 UTC == 2026-04-29 11:00 KST (after 06:00 → same day)
        Instant ts = ZonedDateTime.of(2026, 4, 29, 2, 0, 0, 0, ZoneId.of("UTC")).toInstant();

        assertThat(resolver.resolve(ts, KST)).isEqualTo(LocalDate.of(2026, 4, 29));
    }

    @Test
    void timestampInDifferentZoneShiftsOnceForBoundary() {
        // 2026-04-28 19:30 UTC == 2026-04-29 04:30 KST (before 06:00 → previous day)
        Instant ts = ZonedDateTime.of(2026, 4, 28, 19, 30, 0, 0, ZoneId.of("UTC")).toInstant();

        assertThat(resolver.resolve(ts, KST)).isEqualTo(LocalDate.of(2026, 4, 28));
    }
}
