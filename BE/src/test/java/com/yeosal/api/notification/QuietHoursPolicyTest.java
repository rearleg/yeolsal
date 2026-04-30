package com.yeosal.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuietHoursPolicyTest {

    private final QuietHoursPolicy policy = new QuietHoursPolicy();

    @Test
    @DisplayName("default config (22→08) is quiet at midnight")
    void defaultConfigQuietAtMidnight() {
        assertThat(policy.isQuiet(LocalTime.of(0, 0), (short) 22, (short) 8)).isTrue();
    }

    @Test
    @DisplayName("default config (22→08) is quiet at 23:00")
    void defaultConfigQuietAt23() {
        assertThat(policy.isQuiet(LocalTime.of(23, 0), (short) 22, (short) 8)).isTrue();
    }

    @Test
    @DisplayName("default config (22→08) is quiet at 03:00")
    void defaultConfigQuietAt03() {
        assertThat(policy.isQuiet(LocalTime.of(3, 0), (short) 22, (short) 8)).isTrue();
    }

    @Test
    @DisplayName("default config (22→08) is not quiet at 09:00")
    void defaultConfigAllowedAt09() {
        assertThat(policy.isQuiet(LocalTime.of(9, 0), (short) 22, (short) 8)).isFalse();
    }

    @Test
    @DisplayName("default config (22→08) is not quiet at 21:59")
    void defaultConfigAllowedAt2159() {
        assertThat(policy.isQuiet(LocalTime.of(21, 59), (short) 22, (short) 8)).isFalse();
    }

    @Test
    @DisplayName("inclusive on start, exclusive on end (22:00 = quiet, 08:00 = allowed)")
    void boundaryConditions() {
        assertThat(policy.isQuiet(LocalTime.of(22, 0), (short) 22, (short) 8)).isTrue();
        assertThat(policy.isQuiet(LocalTime.of(8, 0), (short) 22, (short) 8)).isFalse();
    }

    @Test
    @DisplayName("non-wrapping config (08→22) — quiet during the day")
    void nonWrappingConfigQuietDuringDay() {
        assertThat(policy.isQuiet(LocalTime.of(13, 0), (short) 8, (short) 22)).isTrue();
        assertThat(policy.isQuiet(LocalTime.of(23, 0), (short) 8, (short) 22)).isFalse();
    }

    @Test
    @DisplayName("equal start/end → never quiet (effectively disabled)")
    void equalBoundsNeverQuiet() {
        assertThat(policy.isQuiet(LocalTime.of(0, 0), (short) 0, (short) 0)).isFalse();
        assertThat(policy.isQuiet(LocalTime.of(15, 30), (short) 12, (short) 12)).isFalse();
    }
}
