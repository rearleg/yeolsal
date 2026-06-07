package com.yeosal.api.ceremony;

import java.time.Instant;

/**
 * Wire representation of {@link FinalThreePoster}. The {@code svgText}
 * field carries the inline SVG document so Story 7.3's Home tab card can
 * render without a second fetch; {@code pngUrl} is the Kakao-share-bound
 * static URL (nullable when PNG transcode failed — Story 7.3 falls back to
 * the inline SVG).
 */
public record PosterDto(
        long roomId,
        String yearMonth,
        String svgText,
        String pngUrl,
        Instant generatedAt) {

    public static PosterDto from(FinalThreePoster poster) {
        return new PosterDto(
                poster.getRoomId(),
                poster.getYearMonth(),
                poster.getSvgText(),
                poster.getPngUrl(),
                poster.getGeneratedAt());
    }
}
