package io.paperagent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Append-only boundary for the frozen FINALIZATION composite transition.
 * Implementations must ensure at least the requested legal prefix
 * idempotently, reuse the first stored timestamps on replay, and reject a
 * conflicting branch or authority reference. A stored longer prefix is a
 * successful replay when the requested prefix matches its beginning.
 */
public interface ChainFinalizationTransitionPort {
    void advance(AdvanceCommand command);

    record AdvanceCommand(
            String taskId,
            String transitionId,
            String sourceDecisionId,
            String targetIdentityDigest,
            List<StageAuthority> requiredPrefix,
            Instant committedAt) {
        public AdvanceCommand {
            taskId = required(taskId, "taskId");
            transitionId = required(transitionId, "transitionId");
            sourceDecisionId = required(sourceDecisionId, "sourceDecisionId");
            sha256(targetIdentityDigest, "targetIdentityDigest");
            requiredPrefix = List.copyOf(Objects.requireNonNull(
                    requiredPrefix, "requiredPrefix"));
            if (requiredPrefix.isEmpty()
                    || requiredPrefix.get(0).stage()
                    != ChainTransitionStage.OPEN) {
                throw new IllegalArgumentException(
                        "FINALIZATION prefix must start with OPEN");
            }
            List<ChainTransitionStage> committed = new java.util.ArrayList<>();
            for (StageAuthority evidence : requiredPrefix) {
                if (!ChainTransitionType.FINALIZATION.validNextStages(
                        committed).contains(evidence.stage())) {
                    throw new IllegalArgumentException(
                            "requested stages are not a frozen FINALIZATION prefix");
                }
                evidence.validateShape();
                committed.add(evidence.stage());
            }
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    record StageAuthority(
            ChainTransitionStage stage,
            String predecessorAuthorityType,
            String predecessorAuthorityRef,
            String successorAuthorityType,
            String successorAuthorityRef) {
        public StageAuthority {
            Objects.requireNonNull(stage, "stage");
            paired(predecessorAuthorityType, predecessorAuthorityRef,
                    "predecessor authority");
            paired(successorAuthorityType, successorAuthorityRef,
                    "successor authority");
        }

        public static StageAuthority open() {
            return new StageAuthority(
                    ChainTransitionStage.OPEN, null, null, null, null);
        }

        public static StageAuthority complete() {
            return new StageAuthority(
                    ChainTransitionStage.COMPLETE, null, null, null, null);
        }

        public static StageAuthority predecessor(
                ChainTransitionStage stage, String type, String ref) {
            return new StageAuthority(stage, type, ref, null, null);
        }

        public static StageAuthority successor(
                ChainTransitionStage stage, String type, String ref) {
            return new StageAuthority(stage, null, null, type, ref);
        }

        public static StageAuthority noSuccessor(ChainTransitionStage stage) {
            return new StageAuthority(stage, null, null, null, null);
        }

        public static StageAuthority publishFailureHandoff(
                String publishFailureRef,
                String handoffType, String handoffRef) {
            return new StageAuthority(
                    ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED,
                    "PUBLISH_FAILURE", publishFailureRef,
                    handoffType, handoffRef);
        }

        private void validateShape() {
            switch (stage) {
                case OPEN, COMPLETE -> requireNone();
                case READINESS_VERIFIED -> requirePredecessor(
                        "FINALIZATION_READINESS");
                case FINALIZATION_CHECK_COMMITTED -> requireSuccessor(
                        "FINALIZATION_CHECK");
                case PUBLISH_COMMITTED_OR_NOT_REQUIRED -> {
                    if (predecessorAuthorityType != null
                            || (successorAuthorityType != null
                            && !"PUBLISH_RECEIPT".equals(
                            successorAuthorityType))) {
                        invalidShape();
                    }
                }
                case TASK_OUTCOME_COMMITTED -> requireSuccessor(
                        "TASK_OUTCOME");
                case FAILED_CHECK_HANDOFF_COMMITTED -> {
                    if ((predecessorAuthorityType != null
                            && !"PUBLISH_FAILURE".equals(
                            predecessorAuthorityType))
                            || (!"REVIEW_DECISION".equals(
                            successorAuthorityType)
                            && !"TASK_OUTCOME".equals(
                            successorAuthorityType))) {
                        invalidShape();
                    }
                }
                default -> invalidShape();
            }
        }

        private void requireNone() {
            if (predecessorAuthorityType != null
                    || successorAuthorityType != null) {
                invalidShape();
            }
        }

        private void requirePredecessor(String type) {
            if (!type.equals(predecessorAuthorityType)
                    || successorAuthorityType != null) {
                invalidShape();
            }
        }

        private void requireSuccessor(String type) {
            if (predecessorAuthorityType != null
                    || !type.equals(successorAuthorityType)) {
                invalidShape();
            }
        }

        private void invalidShape() {
            throw new IllegalArgumentException(
                    "invalid authority shape for " + stage);
        }
    }

    private static void paired(String type, String ref, String name) {
        if ((type == null) != (ref == null)) {
            throw new IllegalArgumentException(name + " must be all-or-none");
        }
        if (type != null) {
            required(type, name + " type");
            required(ref, name + " ref");
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
