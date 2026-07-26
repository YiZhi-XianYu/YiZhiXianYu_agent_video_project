package com.yizhixianyu.agentvideo.auth;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserAccountEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String status;

    protected UserAccountEntity() {
    }

    public UserAccountEntity(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = "ACTIVE";
    }

    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public String getStatus() { return status; }
}
