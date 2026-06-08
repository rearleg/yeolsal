package com.yeosal.api.ceremony;

import java.time.YearMonth;

/**
 * Story 7.2 — realtime payload signaling a Home tab refresh for the
 * newly-generated Final-3 poster. The FE (Story 7.3) consumes this
 * frame on {@code /topic/rooms.{roomId}.posters} and refetches
 * {@code GET /rooms/{id}/posters/{yearMonth}} (Story 7.1 endpoint) to
 * obtain the SVG + PNG URL.
 *
 * <p>Payload deliberately omits the SVG body — pushing kilobytes of
 * SVG over STOMP for 5K rooms × N subscribers would saturate the
 * broker. The frame is a lightweight "go fetch" signal.
 */
public record MonthlyPosterReadyPayload(long roomId, String yearMonth) {

    public MonthlyPosterReadyPayload {
        if (yearMonth == null) {
            throw new IllegalArgumentException("yearMonth is required");
        }
    }

    public static MonthlyPosterReadyPayload of(long roomId, YearMonth yearMonth) {
        return new MonthlyPosterReadyPayload(roomId, yearMonth.toString());
    }
}
