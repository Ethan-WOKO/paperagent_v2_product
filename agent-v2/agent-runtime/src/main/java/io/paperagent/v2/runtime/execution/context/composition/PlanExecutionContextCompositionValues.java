package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceErrorCode;

import java.util.Set;

final class PlanExecutionContextCompositionValues {
    private static final String EXECUTION_RECOVERY_PATH =
            "executionRecovery";
    private static final String CONTEXT_PATH = "planExecutionContext";

    private static final Set<String> VALIDATION_PATHS = Set.of(
            "planExecutionContextComposition.request",
            "planExecutionContextComposition.request.planId",
            "planExecutionContextComposition.request"
                    + ".proposedMaterializationSpec",
            "planExecutionContextComposition.request.leaseAttempt",
            "planExecutionContextComposition.request"
                    + ".leaseAttempt.leaseOwnerId",
            "planExecutionContextComposition.request"
                    + ".leaseAttempt.leaseToken",
            "planExecutionContextComposition.request"
                    + ".leaseAttempt.leaseExpiresAt",
            "planExecutionContextComposition"
                    + ".executionStartRecoveryRepository",
            "planExecutionContextComposition"
                    + ".planExecutionContextRepository",
            "planExecutionContextComposition.leaseRepository",
            "planExecutionContextComposition.workspacePort",
            "planExecutionContextReady",
            "planExecutionContextReady.resolution",
            "planExecutionContextReady.persistedContext",
            "planExecutionContextReady.verifiedWorkspace",
            "planExecutionContextReady.leaseDisposition",
            "planExecutionContextNotRequired",
            "planExecutionContextNotRequired.planId",
            "planExecutionContextNotRequired.leaseDisposition",
            "planExecutionContextAdvancedUnsupported",
            "planExecutionContextAdvancedUnsupported.planId",
            "planExecutionContextAdvancedUnsupported.stage",
            "planExecutionContextAdvancedUnsupported.failure",
            "planExecutionContextAdvancedUnsupported.leaseDisposition",
            "planExecutionContextPersistenceRejected",
            "planExecutionContextPersistenceRejected.planId",
            "planExecutionContextPersistenceRejected.stage",
            "planExecutionContextPersistenceRejected.failure",
            "planExecutionContextPersistenceRejected.leaseDisposition",
            "planExecutionContextWorkspaceRejected",
            "planExecutionContextWorkspaceRejected.planId",
            "planExecutionContextWorkspaceRejected.stage",
            "planExecutionContextWorkspaceRejected.workspaceErrorCode",
            "planExecutionContextWorkspaceRejected.leaseDisposition",
            "planExecutionContextRetryRequired",
            "planExecutionContextRetryRequired.planId",
            "planExecutionContextRetryRequired.stage",
            "planExecutionContextRetryRequired.retryReason",
            "planExecutionContextRetryRequired.leaseDisposition");

    private static final Set<String> PROTOCOL_BASE_PATHS = Set.of(
            "planExecutionContextComposition"
                    + ".initialExecutionStartInspectResult",
            "planExecutionContextComposition.initialContextInspectResult",
            "planExecutionContextComposition.leaseAcquireResult",
            "planExecutionContextComposition"
                    + ".postLeaseExecutionStartInspectResult",
            "planExecutionContextComposition.postLeaseContextInspectResult",
            "planExecutionContextComposition.reserveResult",
            "planExecutionContextComposition"
                    + ".postReserveContextInspectResult",
            "planExecutionContextComposition.workspaceInspectResult",
            "planExecutionContextComposition.workspaceMaterializeResult",
            "planExecutionContextComposition"
                    + ".postMaterializeWorkspaceInspectResult",
            "planExecutionContextComposition.confirmResult",
            "planExecutionContextComposition"
                    + ".postConfirmContextInspectResult");

    private static final Set<WorkspaceErrorCode> INSPECT_CODES = Set.of(
            WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
            WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
            WorkspaceErrorCode.WORKSPACE_RETIRED,
            WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
            WorkspaceErrorCode.PATH_ESCAPE,
            WorkspaceErrorCode.LINK_ESCAPE);

    private static final Set<WorkspaceErrorCode> POST_MATERIALIZE_CODES =
            Set.of(
                    WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
                    WorkspaceErrorCode.WORKSPACE_RETIRED,
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    WorkspaceErrorCode.PATH_ESCAPE,
                    WorkspaceErrorCode.LINK_ESCAPE);

    private static final Set<WorkspaceErrorCode> MATERIALIZE_CODES = Set.of(
            WorkspaceErrorCode.INVALID_METADATA,
            WorkspaceErrorCode.SOURCE_FAILURE,
            WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH,
            WorkspaceErrorCode.DUPLICATE_PATH,
            WorkspaceErrorCode.PATH_COLLISION,
            WorkspaceErrorCode.HASH_MISMATCH,
            WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
            WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
            WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED,
            WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT,
            WorkspaceErrorCode.WORKSPACE_RETIRED,
            WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
            WorkspaceErrorCode.PATH_ESCAPE,
            WorkspaceErrorCode.LINK_ESCAPE,
            WorkspaceErrorCode.NOT_REGULAR_FILE,
            WorkspaceErrorCode.TEMPORARY_PATH_OCCUPIED,
            WorkspaceErrorCode.SOURCE_MANIFEST_FINGERPRINT_MISMATCH,
            WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
            WorkspaceErrorCode.ATOMIC_PUBLISH_NOT_SUPPORTED,
            WorkspaceErrorCode.IO_FAILURE);

    private PlanExecutionContextCompositionValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw validationFailure(
                    PlanExecutionContextCompositionValidationCode
                            .REQUIRED_VALUE_MISSING,
                    path);
        }
        return value;
    }

    static String identifier(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw validationFailure(
                    PlanExecutionContextCompositionValidationCode
                            .INVALID_IDENTIFIER,
                    path);
        }
        return value;
    }

    static PlanExecutionContextCompositionValidationException
            inconsistentRequest(String path) {
        return validationFailure(
                PlanExecutionContextCompositionValidationCode
                        .INCONSISTENT_REQUEST_AUTHORITY,
                path);
    }

    static PlanExecutionContextCompositionValidationException
            validationFailure(
                    PlanExecutionContextCompositionValidationCode code,
                    String path) {
        String canonicalPath = validationPath(path);
        return new PlanExecutionContextCompositionValidationException(
                requiredInternal(code, "code"),
                canonicalPath,
                "Plan execution-context composition validation failure: "
                        + "code="
                        + code
                        + ", path="
                        + canonicalPath);
    }

    static PlanExecutionContextCompositionProtocolException protocolFailure(
            PlanId planId,
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            PlanExecutionContextLeaseDisposition leaseDisposition,
            Throwable cause) {
        return new PlanExecutionContextCompositionProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                protocolPath(path),
                requiredInternal(leaseDisposition, "leaseDisposition"),
                cause);
    }

    static String validationPath(String path) {
        requiredInternal(path, "path");
        if (!VALIDATION_PATHS.contains(path)) {
            throw new IllegalArgumentException(
                    "path is not in the validation lexicon");
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
        throw new IllegalArgumentException(
                "path is not in the protocol lexicon");
    }

    static void requireReady(
            PlanExecutionContextCompositionResolution resolution,
            PersistedPlanExecutionContextConfirmed persistedContext,
            VerifiedWorkspaceMaterialization verifiedWorkspace,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        if (!verifiedWorkspace.spec().equals(
                        persistedContext.materializationSpec())
                || !verifiedWorkspace.sourceManifestFingerprint().equals(
                        persistedContext.sourceManifestFingerprint())) {
            throw invalidOutcome("planExecutionContextReady.verifiedWorkspace");
        }
        switch (resolution) {
            case OBSERVED_CONFIRMED -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextReady.leaseDisposition",
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
            case CONFIRM_APPLIED, CONFIRM_REPLAYED,
                    RECONCILED_AFTER_RESPONSE_LOSS -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextReady.leaseDisposition",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
            case OBSERVED_CONCURRENT_CONFIRMATION -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextReady.leaseDisposition",
                    PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }
    }

    static void requireAdvanced(
            PlanExecutionContextCompositionStage stage,
            PersistenceFailure failure,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        if (!matches(
                failure,
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                EXECUTION_RECOVERY_PATH)) {
            throw invalidOutcome(
                    "planExecutionContextAdvancedUnsupported.failure");
        }
        switch (stage) {
            case INITIAL_EXECUTION_START_INSPECT -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextAdvancedUnsupported"
                            + ".leaseDisposition",
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
            case POST_LEASE_EXECUTION_START_INSPECT -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextAdvancedUnsupported"
                            + ".leaseDisposition",
                    PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
            default -> throw invalidOutcome(
                    "planExecutionContextAdvancedUnsupported.stage");
        }
    }

    static void requirePersistenceRejected(
            PlanExecutionContextCompositionStage stage,
            PersistenceFailure failure,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        boolean canonical = switch (stage) {
            case INITIAL_EXECUTION_START_INSPECT,
                    POST_LEASE_EXECUTION_START_INSPECT ->
                    isExecutionInspectionFailure(failure);
            case INITIAL_CONTEXT_INSPECT,
                    POST_LEASE_CONTEXT_INSPECT,
                    POST_RESERVE_CONTEXT_INSPECT,
                    POST_CONFIRM_CONTEXT_INSPECT ->
                    isContextPartial(failure);
            case LEASE_ACQUIRE -> isLeaseAcquireFailure(failure);
            case RESERVE -> isReserveFailure(failure);
            case CONFIRM -> isConfirmFailure(failure);
            default -> throw invalidOutcome(
                    "planExecutionContextPersistenceRejected.stage");
        };
        if (!canonical) {
            throw invalidOutcome(
                    "planExecutionContextPersistenceRejected.failure");
        }
        switch (stage) {
            case INITIAL_EXECUTION_START_INSPECT,
                    INITIAL_CONTEXT_INSPECT -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextPersistenceRejected"
                            + ".leaseDisposition",
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
            case LEASE_ACQUIRE -> requireDisposition(
                    leaseDisposition,
                    "planExecutionContextPersistenceRejected"
                            + ".leaseDisposition",
                    PlanExecutionContextLeaseDisposition.NOT_ACQUIRED);
            case POST_LEASE_EXECUTION_START_INSPECT,
                    POST_LEASE_CONTEXT_INSPECT ->
                    requireAcquireDisposition(
                            leaseDisposition,
                            "planExecutionContextPersistenceRejected"
                                    + ".leaseDisposition");
            case RESERVE, POST_RESERVE_CONTEXT_INSPECT,
                    CONFIRM, POST_CONFIRM_CONTEXT_INSPECT ->
                    requireRetained(
                            leaseDisposition,
                            "planExecutionContextPersistenceRejected"
                                    + ".leaseDisposition");
            default -> throw new IllegalStateException(
                    "validated persistence-rejection stage disappeared");
        }
    }

    static void requireWorkspaceRejected(
            PlanExecutionContextCompositionStage stage,
            WorkspaceErrorCode workspaceErrorCode,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        switch (stage) {
            case WORKSPACE_INSPECT -> {
                if (!INSPECT_CODES.contains(workspaceErrorCode)) {
                    throw invalidOutcome(
                            "planExecutionContextWorkspaceRejected"
                                    + ".workspaceErrorCode");
                }
            }
            case WORKSPACE_MATERIALIZE -> {
                if (!MATERIALIZE_CODES.contains(workspaceErrorCode)) {
                    throw invalidOutcome(
                            "planExecutionContextWorkspaceRejected"
                                    + ".workspaceErrorCode");
                }
                requireRetained(
                        leaseDisposition,
                        "planExecutionContextWorkspaceRejected"
                                + ".leaseDisposition");
            }
            case POST_MATERIALIZE_WORKSPACE_INSPECT -> {
                if (!POST_MATERIALIZE_CODES.contains(workspaceErrorCode)) {
                    throw invalidOutcome(
                            "planExecutionContextWorkspaceRejected"
                                    + ".workspaceErrorCode");
                }
                requireRetained(
                        leaseDisposition,
                        "planExecutionContextWorkspaceRejected"
                                + ".leaseDisposition");
            }
            default -> throw invalidOutcome(
                    "planExecutionContextWorkspaceRejected.stage");
        }
    }

    static void requireRetry(
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextRetryReason reason,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        PlanExecutionContextCompositionStage requiredStage = switch (reason) {
            case EXECUTION_START_NOT_COMMITTED ->
                    PlanExecutionContextCompositionStage
                            .INITIAL_EXECUTION_START_INSPECT;
            case RESERVATION_INDETERMINATE ->
                    PlanExecutionContextCompositionStage.RESERVE;
            case MATERIALIZATION_INDETERMINATE ->
                    PlanExecutionContextCompositionStage
                            .WORKSPACE_MATERIALIZE;
            case CONFIRMATION_INDETERMINATE ->
                    PlanExecutionContextCompositionStage.CONFIRM;
        };
        if (stage != requiredStage) {
            throw invalidOutcome(
                    "planExecutionContextRetryRequired.stage");
        }
        PlanExecutionContextLeaseDisposition requiredDisposition =
                reason == PlanExecutionContextRetryReason
                                .EXECUTION_START_NOT_COMMITTED
                        ? PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION
                        : PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY;
        requireDisposition(
                leaseDisposition,
                "planExecutionContextRetryRequired.leaseDisposition",
                requiredDisposition);
    }

    static void requireDisposition(
            PlanExecutionContextLeaseDisposition actual,
            String path,
            PlanExecutionContextLeaseDisposition... allowed) {
        for (PlanExecutionContextLeaseDisposition allowedDisposition
                : allowed) {
            if (actual == allowedDisposition) {
                return;
            }
        }
        throw invalidOutcome(path);
    }

    static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static boolean isExecutionInspectionFailure(
            PersistenceFailure failure) {
        return matches(
                        failure,
                        PersistenceErrorCode.NOT_FOUND,
                        "planId")
                || matches(
                        failure,
                        PersistenceErrorCode
                                .EXECUTION_RECOVERY_PARTIAL_STATE,
                        EXECUTION_RECOVERY_PATH);
    }

    private static boolean isContextPartial(PersistenceFailure failure) {
        return matches(
                failure,
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                CONTEXT_PATH);
    }

    private static boolean isLeaseAcquireFailure(
            PersistenceFailure failure) {
        return matches(
                        failure,
                        PersistenceErrorCode.INVALID_ARGUMENT,
                        "expiresAt")
                || matches(
                        failure,
                        PersistenceErrorCode.NOT_FOUND,
                        "planId")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_HELD,
                        "planId")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "leaseToken");
    }

    private static boolean isReserveFailure(PersistenceFailure failure) {
        return isContextPartial(failure)
                || matches(
                        failure,
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId")
                || matches(
                        failure,
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.materializationSpec.workspaceId")
                || matches(
                        failure,
                        PersistenceErrorCode.NOT_FOUND,
                        "request.planId")
                || matches(
                        failure,
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "planExecutionContext.source")
                || matches(
                        failure,
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "request.materializationSpec"
                                + ".sourceProjectVersion")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "request.leaseToken")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                        "request.fencingToken")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId")
                || stale(failure, "request.expectedRevisionId")
                || stale(failure, "request.expectedRevisionNumber")
                || stale(failure, "request.expectedCheckpointVersion")
                || stale(failure, "request.expectedEventHeadSequence");
    }

    private static boolean isConfirmFailure(PersistenceFailure failure) {
        return isContextPartial(failure)
                || matches(
                        failure,
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId")
                || matches(
                        failure,
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.materializationSpec")
                || matches(
                        failure,
                        PersistenceErrorCode.NOT_FOUND,
                        "request.planId")
                || matches(
                        failure,
                        PersistenceErrorCode.NOT_FOUND,
                        CONTEXT_PATH)
                || matches(
                        failure,
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "planExecutionContext.source")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "request.leaseToken")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                        "request.fencingToken")
                || matches(
                        failure,
                        PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId");
    }

    private static boolean stale(
            PersistenceFailure failure,
            String path) {
        return matches(failure, PersistenceErrorCode.STALE_VERSION, path);
    }

    private static boolean matches(
            PersistenceFailure failure,
            PersistenceErrorCode code,
            String path) {
        return failure.code() == code && failure.path().equals(path);
    }

    private static void requireAcquireDisposition(
            PlanExecutionContextLeaseDisposition leaseDisposition,
            String path) {
        requireDisposition(
                leaseDisposition,
                path,
                PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static void requireRetained(
            PlanExecutionContextLeaseDisposition leaseDisposition,
            String path) {
        requireDisposition(
                leaseDisposition,
                path,
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static PlanExecutionContextCompositionValidationException
            invalidOutcome(String path) {
        return validationFailure(
                PlanExecutionContextCompositionValidationCode
                        .INVALID_OUTCOME_STATE,
                path);
    }
}
