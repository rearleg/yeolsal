package com.yeosal.api.daily;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public record DailyMissionStatus(
        LocalDate date,
        boolean missionCompleted,
        ZonedDateTime reflectionSubmittedAt,
        ZonedDateTime deadline
) {
    static DailyMissionStatus incomplete(LocalDate date) {
        return new DailyMissionStatus(date, false, null, null);
    }
}
