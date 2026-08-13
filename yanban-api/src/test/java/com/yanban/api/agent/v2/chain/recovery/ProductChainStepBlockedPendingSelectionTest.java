package com.yanban.api.agent.v2.chain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductChainStepBlockedPendingSelectionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void terminalPendingItemMustBelongToTheExactBoundReviewProposal() {
        var projection = mock(ProductChainRecoverySource.RoleProjection.class);
        var review = review();
        when(projection.proposals()).thenReturn(List.of(proposal(review)));
        when(projection.pending()).thenReturn(List.of(pending(
                review.value().proposalId(), ChainPendingItemStatus.RESOLVED)));

        var exact = ProductChainNextRoleSelector.pendingAfterReview(
                projection, review);
        assertEquals("gap-1", exact.item().gapId());

        when(projection.pending()).thenReturn(List.of(pending(
                "another-proposal", ChainPendingItemStatus.RESOLVED)));
        assertNull(ProductChainNextRoleSelector.pendingAfterReview(
                projection, review));
        when(projection.proposals()).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () ->
                ProductChainNextRoleSelector.pendingAfterReview(
                        projection, review));
    }

    private static ProductChainRecoverySource.Sequenced<
            ChainPersistenceRecords.ReviewDecisionRecord> review() {
        var value = new ChainPersistenceRecords.ReviewDecisionRecord(
                "review-1", "task-1", "review-event", "reflector-proposal",
                "PROPOSAL_STATE", "executor-accepted-event",
                ChainProposalKind.REFLECTOR_NEED_USER_INPUT,
                "need input", json("[\"executor-accepted-event\"]"),
                HASH, NOW);
        return new ProductChainRecoverySource.Sequenced<>(value, 4);
    }

    private static ProductChainRecoverySource.ProposalProjection proposal(
            ProductChainRecoverySource.Sequenced<
                    ChainPersistenceRecords.ReviewDecisionRecord> review) {
        var invocation = new ChainPersistenceRecords.ModelInvocationRecord(
                "reflector-invocation", "task-1", "reflector-context",
                "completion", ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, "STEP_BLOCKED_REVIEW",
                "provider", "model", 2, "policy", NOW);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                review.value().proposalId(), "task-1",
                invocation.invocationId(), 1, ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_NEED_USER_INPUT,
                json("{}"), json("[]"), null, null, NOW);
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1, "task-1", "reflector-accepted",
                ChainProposalState.ACCEPTED, null, null, NOW);
        var bound = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 2, "task-1", "reflector-bound",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "REVIEW_DECISION", review.value().reviewDecisionId(), NOW);
        return new ProductChainRecoverySource.ProposalProjection(
                null, invocation, proposal, List.of(accepted, bound), bound, 5);
    }

    private static ProductChainRecoverySource.PendingProjection pending(
            String proposalId, ChainPendingItemStatus status) {
        var item = new ChainPersistenceRecords.PendingItemRecord(
                "gap-1", "task-1", "pending-event", proposalId,
                ChainPendingItemType.USER_INFORMATION, HASH,
                json("[\"owner\"]"), null, "owner?", "text",
                ChainRole.EXECUTOR, ChainRole.EXECUTOR, json("{}"), HASH, NOW);
        return new ProductChainRecoverySource.PendingProjection(
                item, status, item.gapId(), 6, 6);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(value), value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
