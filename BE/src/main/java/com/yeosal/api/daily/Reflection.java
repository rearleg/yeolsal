package com.yeosal.api.daily;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "reflections")
public class Reflection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_entry_id", unique = true)
    private DailyEntry dailyEntry;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected Reflection() {}

    public Reflection(DailyEntry dailyEntry, String body) {
        this.dailyEntry = dailyEntry;
        this.body = body;
    }

    @PrePersist
    void prePersist() {
        if (submittedAt == null) {
            submittedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public DailyEntry getDailyEntry() { return dailyEntry; }
    public String getBody() { return body; }
    public Instant getSubmittedAt() { return submittedAt; }
}
