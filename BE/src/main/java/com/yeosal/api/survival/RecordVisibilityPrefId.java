package com.yeosal.api.survival;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link RecordVisibilityPref}. Required by JPA's
 * {@code @IdClass} contract — must implement {@link Serializable}, have a
 * no-arg constructor, and define {@code equals} + {@code hashCode}.
 */
public class RecordVisibilityPrefId implements Serializable {

    private Long userId;
    private Long roomId;

    public RecordVisibilityPrefId() {}

    public RecordVisibilityPrefId(Long userId, Long roomId) {
        this.userId = userId;
        this.roomId = roomId;
    }

    public Long getUserId() { return userId; }
    public Long getRoomId() { return roomId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecordVisibilityPrefId other)) return false;
        return Objects.equals(userId, other.userId) && Objects.equals(roomId, other.roomId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roomId);
    }
}
