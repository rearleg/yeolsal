package com.yeosal.api.daily;

import com.yeosal.api.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "daily_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "entry_date"}))
public class DailyEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "entry_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "dailyEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TodoItem> todos = new ArrayList<>();

    @OneToOne(mappedBy = "dailyEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private Reflection reflection;

    protected DailyEntry() {}

    public DailyEntry(User user, LocalDate date, String goal) {
        this.user = user;
        this.date = date;
        this.goal = goal;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public LocalDate getDate() { return date; }
    public String getGoal() { return goal; }
    public Instant getCreatedAt() { return createdAt; }
    public List<TodoItem> getTodos() { return todos; }
    public Reflection getReflection() { return reflection; }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public void replace(String goal, List<String> todoTitles) {
        this.goal = goal;
        this.todos.clear();
        todoTitles.forEach(title -> this.todos.add(new TodoItem(this, title)));
    }
}
