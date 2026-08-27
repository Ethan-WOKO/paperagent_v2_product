package com.yanban.api.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "invite_codes")
public class InviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "max_uses", nullable = false)
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InviteCode() {
    }

    public InviteCode(String code, Integer maxUses) {
        this.code = code;
        this.maxUses = maxUses;
        this.usedCount = 0;
        this.enabled = true;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void delete() {
        if (deletedAt == null) {
            enabled = false;
            deletedAt = Instant.now();
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
