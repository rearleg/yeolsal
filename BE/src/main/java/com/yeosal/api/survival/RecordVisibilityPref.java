package com.yeosal.api.survival;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-(user, room) opt-in for spectator-mode record sharing (Story 2.3,
 * V11 step 9). A missing row is semantically identical to a row with
 * {@code share_on_elimination = false} — both flows funnel through
 * {@link RecordVisibilityService} which materializes the default.
 *
 * <p>Uses {@link IdClass} composite key — the project has no
 * {@code @EmbeddedId} precedent and this keeps parity with the vanilla
 * JPA pattern used throughout {@code survival/}.
 */
@Entity
@Table(name = "record_visibility_prefs")
@IdClass(RecordVisibilityPrefId.class)
public class RecordVisibilityPref {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "share_on_elimination", nullable = false)
    private boolean shareOnElimination;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecordVisibilityPref() {}

    public RecordVisibilityPref(long userId, long roomId, boolean shareOnElimination) {
        this.userId = userId;
        this.roomId = roomId;
        this.shareOnElimination = shareOnElimination;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public Long getRoomId() { return roomId; }
    public boolean isShareOnElimination() { return shareOnElimination; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Service-only mutator — opt-in toggle flows through {@link RecordVisibilityService}. */
    void setShareOnElimination(boolean shareOnElimination) {
        this.shareOnElimination = shareOnElimination;
    }
}
