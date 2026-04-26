package com.yeosal.api.daily;

import java.time.Instant;

public record Reflection(long id, long dailyEntryId, String body, Instant submittedAt) {}
