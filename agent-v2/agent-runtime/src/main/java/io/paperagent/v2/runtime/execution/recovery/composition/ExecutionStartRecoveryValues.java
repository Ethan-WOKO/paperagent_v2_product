package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;

final class ExecutionStartRecoveryValues {
    private static final String RECOVERY_PATH = "executionRecovery";
    private static final String PLAN_ID_PATH = "planId";

    private ExecutionStartRecoveryValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw validationFailure(
                    ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                    path);
        }
        return value;
    }

    static ExecutionStartRecoveryValidationException validationFailure(
            ExecutionStartRecoveryValidationCode code,
            String path) {
        return new ExecutionStartRecoveryValidationException(
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                "execution-start recovery validation failure: code="
                        + code
                        + ", path="
                        + path);
    }

    static ExecutionStartRecoveryProtocolException protocolFailure(
            PlanId planId,
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryProtocolCode code,
            String path,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition,
            Throwable cause) {
        return new ExecutionStartRecoveryProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                requiredInternal(leaseDisposition, "leaseDisposition"),
                cause);
    }

    static void requireRecoveredCombination(
            ExecutionStartRecoveryResolution resolution,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition) {
        if (resolution != ExecutionStartRecoveryResolution.OBSERVED_COMMITTED
                && leaseDisposition
                        != ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY) {
            throw invalid(
                    "recoveredExecutionStart.leaseDisposition");
        }
    }

    static void requireRejectedCombination(
            ExecutionStartRecoveryStage stage,
            PersistenceFailure failure,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition) {
        switch (stage) {
            case INITIAL_INSPECT -> {
                requireDisposition(
                        leaseDisposition,
                        "executionStartRecoveryRejected.leaseDisposition",
                        ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION);
                requireInspectionFailure(
                        failure,
                        "executionStartRecoveryRejected.failure");
            }
            case LEASE_ACQUIRE -> requireDisposition(
                    leaseDisposition,
                    "executionStartRecoveryRejected.leaseDisposition",
                    ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED);
            case POST_LEASE_INSPECT -> {
                requireDisposition(
                        leaseDisposition,
                        "executionStartRecoveryRejected.leaseDisposition",
                        ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED,
                        ExecutionStartRecoveryLeaseDisposition
                                .ACQUISITION_INDETERMINATE,
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
                requireInspectionFailure(
                        failure,
                        "executionStartRecoveryRejected.failure");
            }
            case ATOMIC_START -> requireDisposition(
                    leaseDisposition,
                    "executionStartRecoveryRejected.leaseDisposition",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
            case POST_START_INSPECT -> {
                requireDisposition(
                        leaseDisposition,
                        "executionStartRecoveryRejected.leaseDisposition",
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
                requireInspectionFailure(
                        failure,
                        "executionStartRecoveryRejected.failure");
            }
            case MATERIALIZE, POST_LEASE_MATERIALIZE ->
                    throw invalid("executionStartRecoveryRejected.stage");
        }
    }

    static void requireAdvancedCombination(
            ExecutionStartRecoveryStage stage,
            PersistenceFailure failure,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition) {
        if (failure.code()
                        != PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE
                || !RECOVERY_PATH.equals(failure.path())) {
            throw invalid(
                    "executionStartRecoveryAdvancedUnsupported.failure");
        }
        switch (stage) {
            case INITIAL_INSPECT -> requireDisposition(
                    leaseDisposition,
                    "executionStartRecoveryAdvancedUnsupported"
                            + ".leaseDisposition",
                    ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION);
            case POST_LEASE_INSPECT -> requireDisposition(
                    leaseDisposition,
                    "executionStartRecoveryAdvancedUnsupported"
                            + ".leaseDisposition",
                    ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED,
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
            case POST_START_INSPECT -> requireDisposition(
                    leaseDisposition,
                    "executionStartRecoveryAdvancedUnsupported"
                            + ".leaseDisposition",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
            case MATERIALIZE, LEASE_ACQUIRE, POST_LEASE_MATERIALIZE,
                    ATOMIC_START ->
                    throw invalid(
                            "executionStartRecoveryAdvancedUnsupported.stage");
        }
    }

    static boolean isNotFound(PersistenceFailure failure) {
        return failure.code() == PersistenceErrorCode.NOT_FOUND
                && PLAN_ID_PATH.equals(failure.path());
    }

    static boolean isPartial(PersistenceFailure failure) {
        return failure.code()
                        == PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE
                && RECOVERY_PATH.equals(failure.path());
    }

    static boolean isAdvanced(PersistenceFailure failure) {
        return failure.code()
                        == PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE
                && RECOVERY_PATH.equals(failure.path());
    }

    private static void requireInspectionFailure(
            PersistenceFailure failure,
            String path) {
        if (!isNotFound(failure) && !isPartial(failure)) {
            throw invalid(path);
        }
    }

    private static void requireDisposition(
            ExecutionStartRecoveryLeaseDisposition actual,
            String path,
            ExecutionStartRecoveryLeaseDisposition... allowed) {
        for (ExecutionStartRecoveryLeaseDisposition allowedDisposition
                : allowed) {
            if (actual == allowedDisposition) {
                return;
            }
        }
        throw invalid(path);
    }

    private static ExecutionStartRecoveryValidationException invalid(
            String path) {
        return validationFailure(
                ExecutionStartRecoveryValidationCode.INVALID_OUTCOME_STATE,
                path);
    }

    private static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
