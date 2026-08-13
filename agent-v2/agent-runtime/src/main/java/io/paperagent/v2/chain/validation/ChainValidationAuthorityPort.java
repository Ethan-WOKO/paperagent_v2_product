package io.paperagent.v2.chain.validation;

import io.paperagent.v2.contracts.ValidationRequirement;

/** Resolves typed frozen requirements to already-existing formal Receipts. */
public interface ChainValidationAuthorityPort {
    VerifiedCandidate verifyCandidate(
            ChainValidationRuntime.Scope scope,
            ValidationRequirement requirement,
            String receiptRef);

    VerifiedActionReceipt verifyActionReceipt(
            ChainValidationRuntime.Scope scope,
            ValidationRequirement requirement,
            String receiptRef);

    record VerifiedCandidate(
            String candidateActionId,
            String validationActionId,
            String receiptId,
            String receiptPayloadSha256,
            String actionSignatureSha256,
            String workspaceCandidateId,
            String workspaceId,
            long artifactId,
            String candidateFingerprint,
            String baseProjectVersion) {
    }

    record VerifiedActionReceipt(
            String actionId,
            String receiptId,
            String receiptPayloadSha256,
            String actionSignatureSha256) {
    }
}
