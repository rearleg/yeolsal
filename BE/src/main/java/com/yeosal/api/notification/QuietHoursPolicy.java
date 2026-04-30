package com.yeosal.api.notification;

import java.time.LocalTime;
import org.springframework.stereotype.Component;

/**
 * Whether a moment in local time falls inside a user's quiet-hours window.
 *
 * <p>The window is half-open {@code [start, end)} expressed in whole hours.
 * When {@code start == end} the window is empty (quiet hours effectively
 * disabled). When {@code start > end} the window wraps midnight (e.g.,
 * 22→08 means "22:00 inclusive through 08:00 exclusive the next morning").
 */
@Component
public class QuietHoursPolicy {

    public boolean isQuiet(LocalTime now, short startHour, short endHour) {
        if (startHour == endHour) {
            return false;
        }
        int hour = now.getHour();
        int start = startHour;
        int end = endHour;
        if (start < end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }
}
