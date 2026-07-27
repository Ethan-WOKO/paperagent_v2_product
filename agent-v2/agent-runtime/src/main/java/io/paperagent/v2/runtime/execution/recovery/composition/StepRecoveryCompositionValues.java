package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;

import java.util.Set;

final class StepRecoveryCompositionValues {
    private static final Set<String> VALIDATION_PATHS = Set.of(
            "stepRecoveryComposition.stepRecoveryRepository",
            "stepRecoveryComposition.leaseRepository",
            "stepRecoveryComposition.request",
            "stepRecoveryLeaseAttempt.leaseOwnerId",
            "stepRecoveryLeaseAttempt.leaseToken",
            "stepRecoveryLeaseAttempt.leaseExpiresAt",
            "stepRecoveryRequest.planId",
            "stepRecoveryRequest.leaseAttempt",
            "recoveredActiveStep.recovery",
            "recoveredActiveStep.lease",
            "recoveredActiveStep.leaseDisposition",
            "stepRecoveryLeaseRejected.planId",
            "stepRecoveryLeaseRejected.failure",
            "stepRecoveryLeaseRejected.leaseDisposition",
            "stepRecoveryPersistenceRejected.planId",
            "stepRecoveryPersistenceRejected.stage",
            "stepRecoveryPersistenceRejected.failure",
            "stepRecoveryPersistenceRejected.leaseDisposition");

    private static final Set<String> PROTOCOL_BASE_PATHS = Set.of(
            "stepRecoveryComposition.initialInspectResult",
            "stepRecoveryComposition.leaseAcquireResult",
            "stepRecoveryComposition.postLeaseInspectResult");

    private StepRecoveryCompositionValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(StepRecoveryValidationCode.REQUIRED_VALUE_MISSING, path);
        }
        return value;
    }

    static String identifier(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw failure(StepRecoveryValidationCode.INVALID_IDENTIFIER, path);
        }
        return value;
    }

    static StepRecoveryValidationException failure(
            StepRecoveryValidationCode code,
            String path) {
        return new StepRecoveryValidationException(
                requiredInternal(code, "code"), requiredInternal(path, "path"));
    }

    static StepRecoveryProtocolException protocolFailure(
            PlanId planId,
            StepRecoveryStage stage,
            StepRecoveryProtocolCode code,
            String path,
            StepRecoveryLeaseDisposition leaseDisposition,
            Throwable cause) {
        return new StepRecoveryProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                requiredInternal(leaseDisposition, "leaseDisposition"),
                cause);
    }

    static void requireRecovered(
            PersistedStepRecoveryActive recovery,
            LeaseRecord lease,
            StepRecoveryLeaseDisposition disposition) {
        required(recovery, "recoveredActiveStep.recovery");
        required(lease, "recoveredActiveStep.lease");
        requireDisposition(
                disposition,
                "recoveredActiveStep.leaseDisposition",
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        if (!recovery.planId().equals(lease.planId())) {
            throw failure(
                    StepRecoveryValidationCode.INCONSISTENT_RECOVERY_AUTHORITY,
                    "recoveredActiveStep.recovery");
        }
    }

    static void requireLeaseRejected(
            PlanId planId,
            PersistenceFailure failure,
            StepRecoveryLeaseDisposition disposition) {
        required(planId, "stepRecoveryLeaseRejected.planId");
        required(failure, "stepRecoveryLeaseRejected.failure");
        requireDisposition(
                disposition,
                "stepRecoveryLeaseRejected.leaseDisposition",
                StepRecoveryLeaseDisposition.NOT_ACQUIRED);
    }

    static void requirePersistenceRejected(
            PlanId planId,
            StepRecoveryStage stage,
            PersistenceFailure failure,
            StepRecoveryLeaseDisposition disposition) {
        required(planId, "stepRecoveryPersistenceRejected.planId");
        required(stage, "stepRecoveryPersistenceRejected.stage");
        required(failure, "stepRecoveryPersistenceRejected.failure");
        if (!isTypedInspectionFailure(failure)) {
            throw invalidOutcome("stepRecoveryPersistenceRejected.failure");
        }
        switch (stage) {
            case INITIAL_INSPECT -> requireDisposition(
                    disposition,
                    "stepRecoveryPersistenceRejected.leaseDisposition",
                    StepRecoveryLeaseDisposition.NO_LEASE_ACTION);
            case POST_LEASE_INSPECT -> requireDisposition(
                    disposition,
                    "stepRecoveryPersistenceRejected.leaseDisposition",
                    StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
            case LEASE_ACQUIRE -> throw invalidOutcome(
                    "stepRecoveryPersistenceRejected.stage");
        }
    }

    static boolean isTypedInspectionFailure(PersistenceFailure failure) {
        return failure.code() == PersistenceErrorCode.NOT_FOUND
                        && "planId".equals(failure.path())
                || failure.code() == PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE
                        && "stepRecovery".equals(failure.path())
                || failure.code() == PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE
                        && "stepRecovery".equals(failure.path());
    }

    static String validationPath(String path) {
        requiredInternal(path, "path");
        if (!VALIDATION_PATHS.contains(path)) {
            throw new IllegalArgumentException("path is not in the validation lexicon");
        }
        return path;
    }

    static String protocolPath(String path) {
        requiredInternal(path, "path");
        for (String base : PROTOCOL_BASE_PATHS) {
            if (path.equals(base)
                    || path.equals(base + ".outcome")
                    || path.equals(base + ".value")
                    || path.equals(base + ".failure")) {
                return path;
            }
        }
        throw new IllegalArgumentException("path is not in the protocol lexicon");
    }

    static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void requireDisposition(
            StepRecoveryLeaseDisposition actual,
            String path,
            StepRecoveryLeaseDisposition expected) {
        required(actual, path);
        if (actual != expected) {
            throw invalidOutcome(path);
        }
    }

    private static StepRecoveryValidationException invalidOutcome(String path) {
        return failure(StepRecoveryValidationCode.INVALID_OUTCOME_STATE, path);
    }
}
