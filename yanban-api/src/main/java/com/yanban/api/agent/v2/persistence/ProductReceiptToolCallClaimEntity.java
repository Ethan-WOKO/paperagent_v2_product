package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "agent_v2_tool_call_claims", uniqueConstraints =
        @UniqueConstraint(
                name = "uk_agent_v2_tool_call_claim_owner",
                columnNames = {"tool_call_id", "owner_kind"}))
class ProductReceiptToolCallClaimEntity {
    @Id
    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;

    @Column(name = "owner_kind", nullable = false, length = 32)
    private String ownerKind;

    protected ProductReceiptToolCallClaimEntity() {
    }

    ProductReceiptToolCallClaimEntity(
            String toolCallId, ProductReceiptOwnership ownerKind) {
        this.toolCallId = toolCallId;
        this.ownerKind = ownerKind.name();
    }

    String toolCallId() {
        return toolCallId;
    }

    String ownerKind() {
        return ownerKind;
    }
}
