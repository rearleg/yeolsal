package com.yeosal.api.kakaoshare;

/**
 * SVG → PNG transcode failure or disk-write failure during a Kakao share
 * preview card render. Mapped to 500 by {@link com.yeosal.api.common.ApiExceptionHandler}
 * with a {@code [kakaoshare]} log prefix so the channel-scoped log convention
 * (project-context line 280) keeps render incidents distinct from generic 5xx.
 */
public class PreviewCardRenderException extends RuntimeException {
    public PreviewCardRenderException(String message) {
        super(message);
    }

    public PreviewCardRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
