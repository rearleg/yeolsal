package com.yeosal.api.ceremony;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Immutable monthly Final-3 poster row (PRD FR-8.7.6). Composite PK
 * {@code (room_id, year_month)} matches V11 step 10 — the second insert for
 * the same key short-circuits at the repository layer in
 * {@link FinalThreeService}, so this entity has no application-level
 * retry-safe upsert. Posters are append-only; no setters for {@code svgText}.
 *
 * <p>Uses {@link IdClass} composite key to stay parallel with the
 * {@code survival/RecordVisibilityPref} precedent. The project has no
 * {@code @EmbeddedId} precedent.
 *
 * <p>{@code columnDefinition = "text"} on {@code svgText} is required because
 * Hibernate would otherwise default to {@code varchar(255)} and fail
 * {@code ddl-auto: validate} against the V11 {@code text} column. {@code @Lob}
 * is the wrong fix — it forces {@code oid} large-object handling which V11
 * does not use.
 */
@Entity
@Table(name = "final_three_posters")
@IdClass(FinalThreePosterId.class)
public class FinalThreePoster {

    @Id
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Id
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "svg_text", nullable = false, columnDefinition = "text")
    private String svgText;

    @Column(name = "png_url", length = 512)
    private String pngUrl;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected FinalThreePoster() {}

    public FinalThreePoster(long roomId, String yearMonth, String svgText, String pngUrl) {
        this.roomId = roomId;
        this.yearMonth = yearMonth;
        this.svgText = svgText;
        this.pngUrl = pngUrl;
    }

    @PrePersist
    void prePersist() {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }

    public Long getRoomId() { return roomId; }

    public String getYearMonth() { return yearMonth; }

    public String getSvgText() { return svgText; }

    public String getPngUrl() { return pngUrl; }

    public Instant getGeneratedAt() { return generatedAt; }
}
