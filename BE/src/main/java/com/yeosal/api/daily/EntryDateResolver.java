package com.yeosal.api.daily;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Resolves the "entry date" for a given timestamp under a 06:00 local-time day
 * boundary. A new day starts at 06:00 in the user's timezone, so any timestamp
 * before 06:00 is attributed to the previous calendar date.
 *
 * <p>Pure utility — no persistence, no clock injection. Callers pass the
 * timestamp and the user's zone explicitly.
 */
@Component
public class EntryDateResolver {
    private static final Duration BOUNDARY_SHIFT = Duration.ofHours(6);

    public LocalDate resolve(Instant timestamp, ZoneId zone) {
        return timestamp.minus(BOUNDARY_SHIFT).atZone(zone).toLocalDate();
    }
}
