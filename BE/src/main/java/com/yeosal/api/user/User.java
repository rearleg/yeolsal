package com.yeosal.api.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, length = 80)
    private String nickname;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 30)
    private AuthProvider authProvider = AuthProvider.EMAIL;

    @Column(nullable = false, length = 80)
    private String timezone = "Asia/Seoul";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Story 3.1 — lifetime-one free revival ticket flag (Architecture §4.12,
     * V11 step 2). Persisted as {@code users.free_revival_ticket_used
     * boolean not null default false}. No JPA setter is exposed; the column
     * is written exclusively through
     * {@code UserRepository.markFreeTicketUsed}'s atomic check-and-set —
     * the lifetime invariant is too important to leave to read-modify-write.
     */
    @Column(name = "free_revival_ticket_used", nullable = false)
    private boolean freeRevivalTicketUsed = false;

    protected User() {}

    public User(String email, String nickname, String passwordHash, AuthProvider authProvider) {
        this.email = email;
        this.nickname = nickname;
        this.passwordHash = passwordHash;
        this.authProvider = authProvider;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (timezone == null) {
            timezone = "Asia/Seoul";
        }
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getNickname() { return nickname; }
    public String getPasswordHash() { return passwordHash; }
    public AuthProvider getAuthProvider() { return authProvider; }
    public String getTimezone() { return timezone; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isFreeRevivalTicketUsed() { return freeRevivalTicketUsed; }

    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setAuthProvider(AuthProvider authProvider) { this.authProvider = authProvider; }
}
