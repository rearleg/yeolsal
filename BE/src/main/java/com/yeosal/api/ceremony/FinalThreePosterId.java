package com.yeosal.api.ceremony;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link FinalThreePoster}. Required by JPA's
 * {@code @IdClass} contract — must implement {@link Serializable}, expose a
 * no-arg constructor, and define {@code equals} + {@code hashCode}. Mirrors
 * the {@code RecordVisibilityPrefId} precedent in {@code survival/}.
 */
public class FinalThreePosterId implements Serializable {

    private Long roomId;
    private String yearMonth;

    public FinalThreePosterId() {}

    public FinalThreePosterId(Long roomId, String yearMonth) {
        this.roomId = roomId;
        this.yearMonth = yearMonth;
    }

    public Long getRoomId() { return roomId; }

    public String getYearMonth() { return yearMonth; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FinalThreePosterId other)) return false;
        return Objects.equals(roomId, other.roomId)
                && Objects.equals(yearMonth, other.yearMonth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, yearMonth);
    }
}
