package com.yanban.api.agent.v2.compatibility.literature;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_v2_literature_deliveries")
class LiteratureDeliveryEntity {
    @EmbeddedId
    private LiteratureDeliveryKey id;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "query_text", nullable = false, length = 1000)
    private String query;
    @Column(name = "top_k", nullable = false)
    private Integer topK;
    @Column(name = "year_from")
    private Integer yearFrom;
    @Column(name = "include_bibtex", nullable = false)
    private Boolean includeBibtex;
    @Column(name = "user_message_id", nullable = false)
    private Long userMessageId;
    @Column(name = "turn_id", nullable = false)
    private Long turnId;
    @Column(name = "lease_owner_id", nullable = false, length = 128)
    private String leaseOwnerId;
    @Column(name = "lease_token", nullable = false, length = 128)
    private String leaseToken;
    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;
    @Column(name = "plan_id", length = 128)
    private String planId;
    @Column(name = "synthesis_id", length = 128)
    private String synthesisId;
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;
    @Column(name = "status", nullable = false, length = 32)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LiteratureDeliveryEntity() {
    }

    LiteratureDeliveryEntity(
            LiteratureDeliveryKey id, String requestSha256,
            String query, Integer topK, Integer yearFrom,
            Boolean includeBibtex,
            Long userMessageId, Long turnId, String leaseOwnerId,
            String leaseToken, Instant leaseExpiresAt, Instant now) {
        this.id = id;
        this.requestSha256 = requestSha256;
        this.query = query;
        this.topK = topK;
        this.yearFrom = yearFrom;
        this.includeBibtex = includeBibtex;
        this.userMessageId = userMessageId;
        this.turnId = turnId;
        this.leaseOwnerId = leaseOwnerId;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.status = "RUNNING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    LiteratureDeliveryKey id() { return id; }
    String requestSha256() { return requestSha256; }
    String query() { return query; }
    Integer topK() { return topK; }
    Integer yearFrom() { return yearFrom; }
    Boolean includeBibtex() { return includeBibtex; }
    Long userMessageId() { return userMessageId; }
    Long turnId() { return turnId; }
    String leaseOwnerId() { return leaseOwnerId; }
    String leaseToken() { return leaseToken; }
    Instant leaseExpiresAt() { return leaseExpiresAt; }
    String planId() { return planId; }
    String synthesisId() { return synthesisId; }
    Long assistantMessageId() { return assistantMessageId; }
    String status() { return status; }
    Instant createdAt() { return createdAt; }

    void bindPlan(String value) {
        if (planId != null && !planId.equals(value)) {
            throw new IllegalStateException("literature plan authority conflict");
        }
        planId = value;
        updatedAt = Instant.now();
    }

    void complete(String plan, String synthesis, Long message) {
        if (planId != null && !planId.equals(plan)
                || synthesisId != null && !synthesisId.equals(synthesis)
                || assistantMessageId != null
                && !assistantMessageId.equals(message)) {
            throw new IllegalStateException(
                    "literature delivery authority conflict");
        }
        planId = plan;
        synthesisId = synthesis;
        assistantMessageId = message;
        status = "DELIVERED";
        updatedAt = Instant.now();
    }

    void rotateLease(String token, Instant expiresAt) {
        leaseToken = token;
        leaseExpiresAt = expiresAt;
        updatedAt = Instant.now();
    }
}
