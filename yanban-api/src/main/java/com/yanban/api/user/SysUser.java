package com.yanban.api.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sys_users")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "invite_code_id")
    private Long inviteCodeId;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType = "NORMAL";

    /** A small first-version role model: USER or ADMIN. */
    @Column(nullable = false, length = 32)
    private String role = "USER";

    /** Incremented at every interactive login so older device tokens become invalid. */
    @Column(name = "login_version", nullable = false)
    private long loginVersion;

    /** -1 means unlimited. Existing accounts intentionally migrate to this value. */
    @Column(name = "ai_quota_total", nullable = false)
    private long aiQuotaTotal = -1L;

    @Column(name = "ai_quota_used", nullable = false)
    private long aiQuotaUsed;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    // The database owns this MySQL ON UPDATE timestamp; Hibernate must not write a stale null value on login.
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SysUser() {
    }

    public SysUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.accountType = "NORMAL";
    }

    public SysUser(String username, String passwordHash, Long inviteCodeId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.inviteCodeId = inviteCodeId;
        this.accountType = "NORMAL";
    }

    public SysUser(String username, String passwordHash, Long inviteCodeId, String accountType) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.inviteCodeId = inviteCodeId;
        this.accountType = accountType == null || accountType.isBlank() ? "NORMAL" : accountType;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Long getInviteCodeId() {
        return inviteCodeId;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType == null || accountType.isBlank() ? "NORMAL" : accountType;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "USER";
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    public long getLoginVersion() {
        return loginVersion;
    }

    public void beginNewLogin() {
        loginVersion++;
        lastLoginAt = Instant.now();
    }

    public void deleteAccount() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            loginVersion++;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public long getAiQuotaTotal() {
        return aiQuotaTotal;
    }

    public long getAiQuotaUsed() {
        return aiQuotaUsed;
    }

    public long getAiQuotaRemaining() {
        return aiQuotaTotal < 0 ? -1L : Math.max(0L, aiQuotaTotal - aiQuotaUsed);
    }

    public void setAiQuotaTotal(long aiQuotaTotal) {
        if (aiQuotaTotal < -1L) {
            throw new IllegalArgumentException("aiQuotaTotal must be -1 or non-negative");
        }
        this.aiQuotaTotal = aiQuotaTotal;
    }

    public void resetAiQuotaUsed() {
        this.aiQuotaUsed = 0L;
    }

    public void addAiQuotaUsage(long tokens) {
        if (tokens <= 0L) {
            return;
        }
        this.aiQuotaUsed = Math.addExact(this.aiQuotaUsed, tokens);
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
