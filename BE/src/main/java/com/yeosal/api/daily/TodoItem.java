package com.yeosal.api.daily;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "todo_items")
public class TodoItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_entry_id")
    private DailyEntry dailyEntry;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TodoItem() {}

    public TodoItem(DailyEntry dailyEntry, String title) {
        this.dailyEntry = dailyEntry;
        this.title = title;
    }

    public Long getId() { return id; }
    public DailyEntry getDailyEntry() { return dailyEntry; }
    public String getTitle() { return title; }
    public boolean isCompleted() { return completed; }
    public Instant getCompletedAt() { return completedAt; }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
        this.completedAt = completed ? Instant.now() : null;
    }
}
