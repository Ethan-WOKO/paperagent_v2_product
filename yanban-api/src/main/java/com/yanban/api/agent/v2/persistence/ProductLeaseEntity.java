package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@IdClass(ProductLeaseId.class)
@Table(name = "agent_v2_plan_leases")
class ProductLeaseEntity {
    @Id
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Id
    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "lease_token", nullable = false, unique = true)
    private String leaseToken;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected ProductLeaseEntity() {
    }

    ProductLeaseEntity(
            String planId,
            long fencingToken,
            String ownerId,
            String leaseToken,
            Instant acquiredAt,
            Instant expiresAt) {
        this.planId = planId;
        this.fencingToken = fencingToken;
        this.ownerId = ownerId;
        this.leaseToken = leaseToken;
        this.acquiredAt = acquiredAt;
        this.expiresAt = expiresAt;
    }

    String planId() {
        return planId;
    }

    long fencingToken() {
        return fencingToken;
    }

    String ownerId() {
        return ownerId;
    }

    String leaseToken() {
        return leaseToken;
    }

    Instant acquiredAt() {
        return acquiredAt;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    Instant releasedAt() {
        return releasedAt;
    }

    void renewUntil(Instant expiry) {
        expiresAt = expiry;
    }

    void releaseAt(Instant instant) {
        releasedAt = instant;
    }
}
