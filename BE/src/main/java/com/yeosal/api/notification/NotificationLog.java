package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "notification_log",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "kind", "key"})
)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationKind kind;

    @Column(name = "key", nullable = false, length = 120)
    private String key;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected NotificationLog() {}

    public NotificationLog(User user, NotificationKind kind, String key) {
        this.user = user;
        this.kind = kind;
        this.key = key;
    }

    @PrePersist
    void prePersist() {
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public NotificationKind getKind() { return kind; }
    public String getKey() { return key; }
    public Instant getSentAt() { return sentAt; }
}
