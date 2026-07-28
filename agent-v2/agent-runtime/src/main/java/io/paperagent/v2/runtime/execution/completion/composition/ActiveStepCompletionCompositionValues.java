package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;

import java.util.Set;

final class ActiveStepCompletionCompositionValues {
    private static final Set<String> VALIDATION_PATHS = Set.of(
            "activeStepCompletionComposition.materializer",
            "activeStepCompletionComposition.repository",
            "activeStepCompletionComposition.request",
            "activeStepCompletionCommitted.persistenceOutcome",
            "activeStepCompletionCommitted.persistedCompletion",
            "activeStepCompletionCommitted.leaseDisposition",
            "activeStepCompletionPersistenceRejected.planId",
            "activeStepCompletionPersistenceRejected.stepId",
            "activeStepCompletionPersistenceRejected.failure",
            "activeStepCompletionPersistenceRejected.leaseDisposition");
    private static final Set<String> PROTOCOL_PATHS = Set.of(
            "activeStepCompletionComposition.materialization",
            "activeStepCompletionComposition.materialization.value",
            "activeStepCompletionComposition.persistence",
            "activeStepCompletionComposition.persistence.outcome",
            "activeStepCompletionComposition.persistence.value",
            "activeStepCompletionComposition.persistence.failure");

    private ActiveStepCompletionCompositionValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            if (!VALIDATION_PATHS.contains(path)) {
                throw new IllegalArgumentException("unknown validation path");
            }
            throw new ActiveStepCompletionCompositionValidationException(
                    ActiveStepCompletionCompositionValidationCode
                            .REQUIRED_VALUE_MISSING,
                    path);
        }
        return value;
    }

    static void requireCommitted(
            PersistenceOutcome outcome,
            PersistedStepCompletion persisted,
            ActiveStepCompletionLeaseDisposition disposition) {
        required(outcome, "activeStepCompletionCommitted.persistenceOutcome");
        if (outcome != PersistenceOutcome.APPLIED
                && outcome != PersistenceOutcome.REPLAYED) {
            throw invalidOutcome(
                    "activeStepCompletionCommitted.persistenceOutcome");
        }
        required(persisted, "activeStepCompletionCommitted.persistedCompletion");
        requireRetained(
                disposition, "activeStepCompletionCommitted.leaseDisposition");
    }

    static void requireRejected(
            PlanId planId,
            PlanStepId stepId,
            PersistenceFailure failure,
            ActiveStepCompletionLeaseDisposition disposition) {
        required(planId, "activeStepCompletionPersistenceRejected.planId");
        required(stepId, "activeStepCompletionPersistenceRejected.stepId");
        required(failure, "activeStepCompletionPersistenceRejected.failure");
        requireRetained(
                disposition,
                "activeStepCompletionPersistenceRejected.leaseDisposition");
    }

    static String protocolPath(String path) {
        requiredInternal(path, "path");
        if (!PROTOCOL_PATHS.contains(path)) {
            throw new IllegalArgumentException("unknown protocol path");
        }
        return path;
    }

    static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void requireRetained(
            ActiveStepCompletionLeaseDisposition disposition,
            String path) {
        required(disposition, path);
        if (disposition
                != ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY) {
            throw invalidOutcome(path);
        }
    }

    private static ActiveStepCompletionCompositionValidationException
            invalidOutcome(String path) {
        return new ActiveStepCompletionCompositionValidationException(
                ActiveStepCompletionCompositionValidationCode
                        .INVALID_OUTCOME_STATE,
                path);
    }
}
