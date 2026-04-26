package com.yeosal.api.daily;

import java.time.Instant;

public record TodoItem(long id, String title, boolean completed, Instant completedAt) {}
