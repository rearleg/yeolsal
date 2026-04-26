package com.yeosal.api.profile;

import java.time.LocalDate;

public record GrassDay(
        LocalDate date,
        boolean missionCompleted,
        int completedTodoCount,
        boolean reflectionSubmitted,
        int intensity
) {}
