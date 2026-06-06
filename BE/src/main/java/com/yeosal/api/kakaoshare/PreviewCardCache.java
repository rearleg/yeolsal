package com.yeosal.api.kakaoshare;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA mapping over {@code room_invite_preview_cache} (V11 (11)). The story
 * does not introduce a new migration — the table predates Story 6.1 and is
 * widened only through entity mapping. The primary key is {@code room_id}
 * with an {@code ON DELETE CASCADE} from {@code rooms} so room deletion
 * reaps cache rows automatically.
 */
@Entity
@Table(name = "room_invite_preview_cache")
public class PreviewCardCache {

    @Id
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "png_url", length = 512, nullable = false)
    private String pngUrl;

    @Column(name = "rendered_at", nullable = false)
    private Instant renderedAt;

    /**
     * Audit-only — captures which rule version drove the render. The cache
     * service does not look this column up; AC4 invalidation deletes the
     * row outright instead of comparing rule versions (see story trap #8).
     */
    @Column(name = "rule_version_id")
    private Long ruleVersionId;

    @Column(name = "member_count_at_render", nullable = false)
    private short memberCountAtRender;

    protected PreviewCardCache() {}

    public PreviewCardCache(long roomId, String pngUrl, Instant renderedAt,
                            Long ruleVersionId, int memberCountAtRender) {
        this.roomId = roomId;
        this.pngUrl = pngUrl;
        this.renderedAt = renderedAt;
        this.ruleVersionId = ruleVersionId;
        this.memberCountAtRender = (short) Math.min(Short.MAX_VALUE, memberCountAtRender);
    }

    public Long getRoomId() { return roomId; }
    public String getPngUrl() { return pngUrl; }
    public Instant getRenderedAt() { return renderedAt; }
    public Long getRuleVersionId() { return ruleVersionId; }
    public int getMemberCountAtRender() { return memberCountAtRender; }

    public void setPngUrl(String pngUrl) { this.pngUrl = pngUrl; }
    public void setRenderedAt(Instant renderedAt) { this.renderedAt = renderedAt; }
    public void setRuleVersionId(Long ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public void setMemberCountAtRender(int v) {
        this.memberCountAtRender = (short) Math.min(Short.MAX_VALUE, v);
    }
}
