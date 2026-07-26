package com.yizhixianyu.agentvideo.auth;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    protected AuthSessionEntity() {
    }

    public AuthSessionEntity(String userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public void revoke() { this.revokedAt = Instant.now(); }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
