package com.yeosal.api.daily;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DailyMissionCalculator {
    private final ZoneId defaultZone;

    public DailyMissionCalculator() {
        this(ZoneId.of("Asia/Seoul"));
    }

    public DailyMissionCalculator(ZoneId defaultZone) {
        this.defaultZone = defaultZone;
    }

    public DailyMissionStatus calculate(LocalDate entryDate, boolean entryPosted, ZonedDateTime reflectionSubmittedAt) {
        if (!entryPosted || reflectionSubmittedAt == null) {
            return DailyMissionStatus.incomplete(entryDate);
        }

        ZonedDateTime deadline = entryDate.plusDays(1).atTime(6, 0).atZone(defaultZone);
        boolean completed = reflectionSubmittedAt.withZoneSameInstant(defaultZone).isBefore(deadline);
        return new DailyMissionStatus(entryDate, completed, reflectionSubmittedAt, deadline);
    }
}
