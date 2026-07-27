package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_v2_effect_results")
class ProductEffectOutcomeResultEntity {
    @Id
    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;
    @Column(name = "receipt_id", nullable = false, unique = true, length = 128)
    private String receiptId;
    @Column(name = "lease_owner_id", nullable = false)
    private String leaseOwnerId;
    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;
    @Column(name = "request_format_version", nullable = false)
    private int requestFormatVersion;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;
    @Column(name = "result_format_version", nullable = false)
    private int resultFormatVersion;
    @Column(name = "result_sha256", nullable = false, length = 64)
    private String resultSha256;
    @Column(name = "result_json", nullable = false, columnDefinition = "LONGTEXT")
    private String resultJson;
    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tool_call_id",
            referencedColumnName = "tool_call_id",
            insertable = false,
            updatable = false)
    private ProductEffectIntentEntity intent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "receipt_id",
                    referencedColumnName = "receipt_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "tool_call_id",
                    referencedColumnName = "tool_call_id",
                    insertable = false,
                    updatable = false)
    })
    private ProductReceiptEntity receipt;

    protected ProductEffectOutcomeResultEntity() {
    }

    ProductEffectOutcomeResultEntity(
            String toolCallId,
            String receiptId,
            String leaseOwnerId,
            long fencingToken,
            ProductEffectOutcomeCodec.EncodedPayload request,
            ProductEffectOutcomeCodec.EncodedPayload result,
            Instant committedAt) {
        this.toolCallId = toolCallId;
        this.receiptId = receiptId;
        this.leaseOwnerId = leaseOwnerId;
        this.fencingToken = fencingToken;
        this.requestFormatVersion = request.formatVersion();
        this.requestSha256 = request.sha256();
        this.requestJson = request.json();
        this.resultFormatVersion = result.formatVersion();
        this.resultSha256 = result.sha256();
        this.resultJson = result.json();
        this.committedAt = committedAt;
    }

    String toolCallId() { return toolCallId; }
    String receiptId() { return receiptId; }
    String leaseOwnerId() { return leaseOwnerId; }
    long fencingToken() { return fencingToken; }
    int requestFormatVersion() { return requestFormatVersion; }
    String requestSha256() { return requestSha256; }
    String requestJson() { return requestJson; }
    int resultFormatVersion() { return resultFormatVersion; }
    String resultSha256() { return resultSha256; }
    String resultJson() { return resultJson; }
    Instant committedAt() { return committedAt; }
}
