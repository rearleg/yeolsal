package com.yeosal.api.ceremony;

import com.yeosal.api.common.NotFoundException;
import java.time.YearMonth;

/**
 * 404 marker for missing Final-3 posters. Extends {@link NotFoundException}
 * so the existing {@code ApiExceptionHandler.notFound(...)} mapping fires
 * automatically — no second {@code @ExceptionHandler} is needed (project
 * context line 87).
 */
public class PosterNotFoundException extends NotFoundException {

    public PosterNotFoundException(long roomId, YearMonth yearMonth) {
        super("포스터를 찾을 수 없습니다. roomId=" + roomId + " yearMonth=" + yearMonth);
    }
}
