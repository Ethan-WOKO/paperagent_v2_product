package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "agent_v2_receipts",
        indexes = @Index(
                name = "idx_agent_v2_receipts_tool_call",
                columnList = "tool_call_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_v2_receipts_id_tool_call",
                columnNames = {"receipt_id", "tool_call_id"}))
class ProductReceiptEntity {
    @Id
    @Column(name = "receipt_id", nullable = false, length = 128)
    private String receiptId;
    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;
    @Column(
            name = "tool_call_claim_owner_kind",
            nullable = false,
            length = 32)
    private String toolCallClaimOwnerKind;
    @Column(name = "receipt_owner_kind", nullable = false, length = 32)
    private String receiptOwnerKind;
    @Column(name = "payload_format_version", nullable = false)
    private int payloadFormatVersion;
    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;
    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "tool_call_id",
                    referencedColumnName = "tool_call_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "tool_call_claim_owner_kind",
                    referencedColumnName = "owner_kind",
                    insertable = false,
                    updatable = false)
    })
    private ProductReceiptToolCallClaimEntity ownershipClaim;

    protected ProductReceiptEntity() {
    }

    ProductReceiptEntity(
            String receiptId,
            String toolCallId,
            ProductReceiptCodec.EncodedPayload payload,
            Instant committedAt) {
        this(receiptId, toolCallId,
                ProductReceiptOwnership.ORDINARY_RECEIPT,
                ProductReceiptOwnership.ORDINARY_RECEIPT.name(),
                payload, committedAt);
    }

    ProductReceiptEntity(
            String receiptId,
            String toolCallId,
            ProductReceiptOwnership claimOwner,
            String receiptOwner,
            ProductReceiptCodec.EncodedPayload payload,
            Instant committedAt) {
        this.receiptId = receiptId;
        this.toolCallId = toolCallId;
        this.toolCallClaimOwnerKind = claimOwner.name();
        this.receiptOwnerKind = receiptOwner;
        this.payloadFormatVersion = payload.formatVersion();
        this.payloadSha256 = payload.sha256();
        this.payloadJson = payload.json();
        this.committedAt = committedAt;
    }

    String receiptId() { return receiptId; }
    String toolCallId() { return toolCallId; }
    String toolCallClaimOwnerKind() { return toolCallClaimOwnerKind; }
    String receiptOwnerKind() { return receiptOwnerKind; }
    int payloadFormatVersion() { return payloadFormatVersion; }
    String payloadSha256() { return payloadSha256; }
    String payloadJson() { return payloadJson; }
    Instant committedAt() { return committedAt; }
}
