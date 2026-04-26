package com.yeosal.api.daily;

import com.yeosal.api.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "monthly_goals", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "month"}))
public class MonthlyGoal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    protected MonthlyGoal() {}

    public MonthlyGoal(User user, String month, String goal) {
        this.user = user;
        this.month = month;
        this.goal = goal;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getMonth() { return month; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
}
