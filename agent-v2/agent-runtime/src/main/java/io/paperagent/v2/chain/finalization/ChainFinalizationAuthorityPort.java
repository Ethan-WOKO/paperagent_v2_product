package io.paperagent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;

import java.util.Objects;
import java.util.Optional;

/** Read-only projection of stable authorities used for mechanical finalization. */
public interface ChainFinalizationAuthorityPort {
    Inspection inspect(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness);

    /** Returns only an already committed formal handoff for a failed check. */
    default Optional<FailureHandoff> findFailureHandoff(
            FailureHandoffQuery query) {
        Objects.requireNonNull(query, "query");
        return Optional.empty();
    }

    sealed interface Inspection permits Available, TemporarilyUnavailable {
    }

    record TemporarilyUnavailable(String authorityRef) implements Inspection {
        public TemporarilyUnavailable {
            authorityRef = required(authorityRef, "authorityRef");
        }
    }

    record Available(
            String taskId,
            String currentInstructionId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            long planRevisionNumber,
            String finalStepId,
            String reviewDecisionId,
            String acceptedSetSha256,
            long applicabilityCutEventSequence,
            boolean taskContractSatisfied,
            String coverageSha256,
            Candidate candidate,
            boolean validationRequired,
            Validation validation,
            ChainPublishRequirement publishRequirement,
            String publishRequirementDigest,
            String currentProjectVersion) implements Inspection {
        public Available {
            taskId = required(taskId, "taskId");
            currentInstructionId = required(
                    currentInstructionId, "currentInstructionId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            planId = required(planId, "planId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            if (planRevisionNumber < 1) {
                throw new IllegalArgumentException(
                        "planRevisionNumber must be positive");
            }
            finalStepId = required(finalStepId, "finalStepId");
            reviewDecisionId = required(reviewDecisionId, "reviewDecisionId");
            sha256(acceptedSetSha256, "acceptedSetSha256");
            if (applicabilityCutEventSequence < 0) {
                throw new IllegalArgumentException(
                        "applicabilityCutEventSequence must not be negative");
            }
            sha256(coverageSha256, "coverageSha256");
            Objects.requireNonNull(publishRequirement, "publishRequirement");
            sha256(publishRequirementDigest, "publishRequirementDigest");
            currentProjectVersion = required(
                    currentProjectVersion, "currentProjectVersion");
        }
    }

    record Candidate(
            String candidateKey,
            String workspaceId,
            long artifactId,
            String fingerprint,
            String baseProjectVersion) {
        public Candidate {
            candidateKey = required(candidateKey, "candidateKey");
            workspaceId = required(workspaceId, "workspaceId");
            if (artifactId < 1) {
                throw new IllegalArgumentException("artifactId must be positive");
            }
            sha256(fingerprint, "fingerprint");
            baseProjectVersion = required(
                    baseProjectVersion, "baseProjectVersion");
        }
    }

    record Validation(
            String validationId,
            Long candidateArtifactId,
            String candidateFingerprint,
            String projectVersion,
            String requestDigest,
            String receiptDigest,
            Status status) {
        public Validation {
            validationId = required(validationId, "validationId");
            if (candidateArtifactId != null && candidateArtifactId < 1) {
                throw new IllegalArgumentException(
                        "candidateArtifactId must be positive");
            }
            if (candidateFingerprint != null) {
                sha256(candidateFingerprint, "candidateFingerprint");
            }
            projectVersion = required(projectVersion, "projectVersion");
            sha256(requestDigest, "requestDigest");
            sha256(receiptDigest, "receiptDigest");
            Objects.requireNonNull(status, "status");
        }

        public enum Status {
            SUCCESSFUL,
            FAILED,
            IN_PROGRESS
        }
    }

    record FailureHandoffQuery(
            String taskId,
            String finalizationTransitionId,
            String finalizationCheckId) {
        public FailureHandoffQuery {
            taskId = required(taskId, "taskId");
            finalizationTransitionId = required(
                    finalizationTransitionId, "finalizationTransitionId");
            finalizationCheckId = required(
                    finalizationCheckId, "finalizationCheckId");
        }
    }

    record FailureHandoff(String authorityType, String authorityRef) {
        public FailureHandoff {
            authorityType = required(authorityType, "authorityType");
            authorityRef = required(authorityRef, "authorityRef");
            if (!"REVIEW_DECISION".equals(authorityType)
                    && !"TASK_OUTCOME".equals(authorityType)) {
                throw new IllegalArgumentException(
                        "failed-check handoff must be ReviewDecision or TaskOutcome");
            }
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void sha256(String value, String name) {
        required(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be lowercase SHA-256");
        }
    }
}
