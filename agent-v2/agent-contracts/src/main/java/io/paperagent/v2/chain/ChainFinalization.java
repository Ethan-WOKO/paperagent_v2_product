package io.paperagent.v2.chain;

import java.util.List;
import java.util.Objects;

public final class ChainFinalization {
    private ChainFinalization() {
    }

    public record Readiness(
            String readinessId,
            ChainIdentity.Readiness identity,
            String finalStepId,
            String reviewDecisionId,
            List<String> acceptedResultIds,
            long applicabilityEventCut,
            String requirementCoverageDigest,
            String publishRequirementDigest) {
        public Readiness {
            readinessId = ChainValues.required(readinessId, "readinessId");
            identity = Objects.requireNonNull(identity, "identity");
            finalStepId = ChainValues.required(finalStepId, "finalStepId");
            reviewDecisionId = ChainValues.required(reviewDecisionId, "reviewDecisionId");
            acceptedResultIds = ChainValues.nonEmptyCopy(acceptedResultIds, "acceptedResultIds");
            if (applicabilityEventCut < 0) {
                throw new IllegalArgumentException("applicabilityEventCut must not be negative");
            }
            requirementCoverageDigest = ChainValues.requiredSha256(
                    requirementCoverageDigest, "requirementCoverageDigest");
            publishRequirementDigest = ChainValues.requiredSha256(
                    publishRequirementDigest, "publishRequirementDigest");
        }
    }

    public record CheckResult(
            String checkId,
            String readinessId,
            int attempt,
            String inputDigest,
            String publishRequirementDigest,
            String policyVersion,
            Outcome outcome,
            FailureHandling failureHandling,
            ErrorCode errorCode) {
        public CheckResult {
            checkId = ChainValues.required(checkId, "checkId");
            readinessId = ChainValues.required(readinessId, "readinessId");
            ChainRuntimePolicy policy = ChainRuntimePolicy.requireVersion(
                    policyVersion);
            if (attempt < 1 || attempt
                    > policy.finalizationMechanicalAttemptsTotal()) {
                throw new IllegalArgumentException("attempt exceeds the finalization mechanical-attempt policy");
            }
            inputDigest = ChainValues.requiredSha256(inputDigest, "inputDigest");
            publishRequirementDigest = ChainValues.requiredSha256(
                    publishRequirementDigest, "publishRequirementDigest");
            policyVersion = ChainValues.required(policyVersion, "policyVersion");
            outcome = Objects.requireNonNull(outcome, "outcome");
            failureHandling = Objects.requireNonNull(failureHandling, "failureHandling");
            if (outcome == Outcome.PASSED && (failureHandling != FailureHandling.NONE || errorCode != null)) {
                throw new IllegalArgumentException("passed check cannot carry failure handling or error code");
            }
            if (outcome == Outcome.FAILED && (failureHandling == FailureHandling.NONE || errorCode == null)) {
                throw new IllegalArgumentException("failed check requires failure handling and error code");
            }
            if (failureHandling == FailureHandling.RETRYABLE
                    && errorCode != ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE) {
                throw new IllegalArgumentException("only temporary authority failure is mechanically retryable");
            }
        }
    }

    public enum Outcome { PASSED, FAILED }

    public enum FailureHandling { NONE, RETRYABLE, REFLECTOR_REQUIRED }

    public enum ErrorCode {
        READINESS_BINDING_MISMATCH,
        TASK_CONTRACT_UNSATISFIED,
        ACCEPTED_RESULT_SET_MISMATCH,
        CANDIDATE_BINDING_MISMATCH,
        VALIDATION_MISSING,
        VALIDATION_NOT_SUCCESSFUL,
        VALIDATION_BINDING_MISMATCH,
        PUBLISH_REQUIREMENT_MISMATCH,
        STALE_VERSION_FENCE,
        AUTHORITY_TEMPORARILY_UNAVAILABLE
    }
}
