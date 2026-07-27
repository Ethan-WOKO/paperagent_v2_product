package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;

import java.util.Set;

final class ActiveStepInterruptionCompositionValues {
    private static final Set<String> VALIDATION_PATHS = Set.of(
            "activeStepInterruptionComposition.materializer",
            "activeStepInterruptionComposition.repository",
            "activeStepInterruptionComposition.request",
            "activeStepInterruptionCommitted.persistenceOutcome",
            "activeStepInterruptionCommitted.persistedInterruption",
            "activeStepInterruptionCommitted.leaseDisposition",
            "activeStepInterruptionPersistenceRejected.planId",
            "activeStepInterruptionPersistenceRejected.failure",
            "activeStepInterruptionPersistenceRejected.leaseDisposition");
    private static final Set<String> PROTOCOL_PATHS = Set.of(
            "activeStepInterruptionComposition.materialization",
            "activeStepInterruptionComposition.materialization.value",
            "activeStepInterruptionComposition.persistence",
            "activeStepInterruptionComposition.persistence.outcome",
            "activeStepInterruptionComposition.persistence.value",
            "activeStepInterruptionComposition.persistence.failure");

    private ActiveStepInterruptionCompositionValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            if (!VALIDATION_PATHS.contains(path)) {
                throw new IllegalArgumentException("unknown validation path");
            }
            throw new ActiveStepInterruptionCompositionValidationException(
                    ActiveStepInterruptionCompositionValidationCode
                            .REQUIRED_VALUE_MISSING,
                    path);
        }
        return value;
    }

    static void requireCommitted(
            PersistenceOutcome outcome,
            PersistedStepInterruption persisted,
            ActiveStepInterruptionLeaseDisposition disposition) {
        required(outcome, "activeStepInterruptionCommitted.persistenceOutcome");
        if (outcome != PersistenceOutcome.APPLIED
                && outcome != PersistenceOutcome.REPLAYED) {
            throw invalidOutcome(
                    "activeStepInterruptionCommitted.persistenceOutcome");
        }
        required(
                persisted,
                "activeStepInterruptionCommitted.persistedInterruption");
        requireRetained(
                disposition,
                "activeStepInterruptionCommitted.leaseDisposition");
    }

    static void requireRejected(
            PlanId planId,
            PersistenceFailure failure,
            ActiveStepInterruptionLeaseDisposition disposition) {
        required(
                planId,
                "activeStepInterruptionPersistenceRejected.planId");
        required(
                failure,
                "activeStepInterruptionPersistenceRejected.failure");
        requireRetained(
                disposition,
                "activeStepInterruptionPersistenceRejected.leaseDisposition");
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
            ActiveStepInterruptionLeaseDisposition disposition,
            String path) {
        required(disposition, path);
        if (disposition
                != ActiveStepInterruptionLeaseDisposition
                        .RETAINED_FOR_RECOVERY) {
            throw invalidOutcome(path);
        }
    }

    private static ActiveStepInterruptionCompositionValidationException
            invalidOutcome(String path) {
        return new ActiveStepInterruptionCompositionValidationException(
                ActiveStepInterruptionCompositionValidationCode
                        .INVALID_OUTCOME_STATE,
                path);
    }
}
