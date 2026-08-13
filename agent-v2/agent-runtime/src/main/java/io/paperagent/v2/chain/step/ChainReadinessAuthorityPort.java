package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPublishRequirement;

import java.util.List;

/** Produces readiness material only after all formal dependencies are verified. */
@FunctionalInterface
public interface ChainReadinessAuthorityPort {
    VerifiedReadinessMaterial verify(ReadinessQuery query);

    record ReadinessQuery(
            String taskId,
            String transitionId,
            String reviewDecisionId) {
    }

    record VerifiedReadinessMaterial(
            String taskFrameId,
            String finalPlanId,
            String finalPlanRevisionId,
            long finalPlanRevisionNumber,
            String finalStepId,
            String activationEventId,
            List<String> acceptedResultIds,
            CanonicalJson acceptedSet,
            long applicabilityCutEventSequence,
            Long artifactId,
            String candidateKey,
            String workspaceId,
            // V73 compatibility names: this triple is the plan-level
            // ValidationBundle id/request/receipt-set identity at readiness.
            String validationId,
            String validationRequestDigest,
            String validationReceiptDigest,
            CanonicalJson coverage,
            ChainPublishRequirement publishRequirement,
            String publishRequirementDigest,
            String instructionId,
            String projectVersion) {
        public VerifiedReadinessMaterial {
            acceptedResultIds = List.copyOf(acceptedResultIds);
            if (acceptedResultIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "verified readiness requires accepted results");
            }
        }
    }
}
