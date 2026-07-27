package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.FINGERPRINT;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.NULL;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.PLAN_ID;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.SECRET;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.WRONG_FINGERPRINT;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.acquireFailures;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.actionThenThrow;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.committed;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.confirmFailures;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.contextPartial;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.executionAdvanced;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.executionNotFound;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.executionPartial;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.failure;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.persistedContext;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.reserveFailures;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.secretNonCanonicalFailure;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.spec;
import static io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionTestFixtures.verifiedWorkspace;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultPlanExecutionContextComposerTest {
    private static final List<PlanExecutionContextCompositionStage> STAGES =
            List.of(PlanExecutionContextCompositionStage.values());
    private static final List<PlanExecutionContextLeaseDisposition>
            DISPOSITIONS =
            List.of(PlanExecutionContextLeaseDisposition.values());
    private static final DefaultPlanExecutionContextComposer.Captured
            NULL_CAPTURE =
            new DefaultPlanExecutionContextComposer.Captured(null, null);

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

    @Test
    void confirmationDoesNotRetainAContinuationAuthorityObject() {
        assertFalse(Arrays.stream(
                        DefaultPlanExecutionContextComposer.class
                                .getDeclaredClasses())
                .map(Class::getSimpleName)
                .anyMatch(name -> name.contains("PendingConfirmation")));
    }

    @Test
    void requestAndLeaseAttemptUseExactValidationPaths() {
        assertValidation(
                () -> new PlanExecutionContextCompositionRequest(
                        null,
                        Optional.empty(),
                        Optional.empty()),
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                "planExecutionContextComposition.request.planId");
        assertValidation(
                () -> new PlanExecutionContextCompositionRequest(
                        PLAN_ID,
                        null,
                        Optional.empty()),
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                "planExecutionContextComposition.request"
                        + ".proposedMaterializationSpec");
        assertValidation(
                () -> new PlanExecutionContextCompositionRequest(
                        PLAN_ID,
                        Optional.empty(),
                        null),
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                "planExecutionContextComposition.request.leaseAttempt");
        assertValidation(
                () -> new PlanExecutionContextLeaseAttempt(
                        null,
                        "token",
                        Instant.EPOCH),
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseOwnerId");
        assertValidation(
                () -> new PlanExecutionContextLeaseAttempt(
                        " ",
                        "token",
                        Instant.EPOCH),
                PlanExecutionContextCompositionValidationCode
                        .INVALID_IDENTIFIER,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseOwnerId");
        assertValidation(
                () -> new PlanExecutionContextLeaseAttempt(
                        "owner",
                        "",
                        Instant.EPOCH),
                PlanExecutionContextCompositionValidationCode
                        .INVALID_IDENTIFIER,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseToken");
        assertValidation(
                () -> new PlanExecutionContextLeaseAttempt(
                        "owner",
                        "token",
                        null),
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseExpiresAt");
    }

    @Test
    void everyOutcomeComponentRejectsNullAtItsExactPath() {
        var exactSpec = spec("null-components");
        var persisted = persistedContext(exactSpec, FINGERPRINT);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        PersistenceFailure advanced = executionAdvanced();
        PersistenceFailure rejected = executionNotFound();

        assertRequired(
                () -> new PlanExecutionContextReady(
                        null,
                        persisted,
                        verified,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextReady.resolution");
        assertRequired(
                () -> new PlanExecutionContextReady(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        null,
                        verified,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextReady.persistedContext");
        assertRequired(
                () -> new PlanExecutionContextReady(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        persisted,
                        null,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextReady.verifiedWorkspace");
        assertRequired(
                () -> new PlanExecutionContextReady(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        persisted,
                        verified,
                        null),
                "planExecutionContextReady.leaseDisposition");

        assertRequired(
                () -> new PlanExecutionContextNotRequired(
                        null,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextNotRequired.planId");
        assertRequired(
                () -> new PlanExecutionContextNotRequired(PLAN_ID, null),
                "planExecutionContextNotRequired.leaseDisposition");

        assertRequired(
                () -> new PlanExecutionContextAdvancedUnsupported(
                        null,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        advanced,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextAdvancedUnsupported.planId");
        assertRequired(
                () -> new PlanExecutionContextAdvancedUnsupported(
                        PLAN_ID,
                        null,
                        advanced,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextAdvancedUnsupported.stage");
        assertRequired(
                () -> new PlanExecutionContextAdvancedUnsupported(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        null,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextAdvancedUnsupported.failure");
        assertRequired(
                () -> new PlanExecutionContextAdvancedUnsupported(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        advanced,
                        null),
                "planExecutionContextAdvancedUnsupported.leaseDisposition");

        assertRequired(
                () -> new PlanExecutionContextPersistenceRejected(
                        null,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        rejected,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextPersistenceRejected.planId");
        assertRequired(
                () -> new PlanExecutionContextPersistenceRejected(
                        PLAN_ID,
                        null,
                        rejected,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextPersistenceRejected.stage");
        assertRequired(
                () -> new PlanExecutionContextPersistenceRejected(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        null,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextPersistenceRejected.failure");
        assertRequired(
                () -> new PlanExecutionContextPersistenceRejected(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        rejected,
                        null),
                "planExecutionContextPersistenceRejected.leaseDisposition");

        assertRequired(
                () -> new PlanExecutionContextWorkspaceRejected(
                        null,
                        PlanExecutionContextCompositionStage
                                .WORKSPACE_INSPECT,
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextWorkspaceRejected.planId");
        assertRequired(
                () -> new PlanExecutionContextWorkspaceRejected(
                        PLAN_ID,
                        null,
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextWorkspaceRejected.stage");
        assertRequired(
                () -> new PlanExecutionContextWorkspaceRejected(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .WORKSPACE_INSPECT,
                        null,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextWorkspaceRejected.workspaceErrorCode");
        assertRequired(
                () -> new PlanExecutionContextWorkspaceRejected(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .WORKSPACE_INSPECT,
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        null),
                "planExecutionContextWorkspaceRejected.leaseDisposition");

        assertRequired(
                () -> new PlanExecutionContextRetryRequired(
                        null,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        PlanExecutionContextRetryReason
                                .EXECUTION_START_NOT_COMMITTED,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextRetryRequired.planId");
        assertRequired(
                () -> new PlanExecutionContextRetryRequired(
                        PLAN_ID,
                        null,
                        PlanExecutionContextRetryReason
                                .EXECUTION_START_NOT_COMMITTED,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextRetryRequired.stage");
        assertRequired(
                () -> new PlanExecutionContextRetryRequired(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        null,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextRetryRequired.retryReason");
        assertRequired(
                () -> new PlanExecutionContextRetryRequired(
                        PLAN_ID,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        PlanExecutionContextRetryReason
                                .EXECUTION_START_NOT_COMMITTED,
                        null),
                "planExecutionContextRetryRequired.leaseDisposition");
    }

    @Test
    void readyFiveByFourMatrixAndAuthorityEqualityAreExact() {
        var exactSpec = spec("ready-matrix");
        var persisted = persistedContext(exactSpec, FINGERPRINT);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        for (PlanExecutionContextCompositionResolution resolution
                : PlanExecutionContextCompositionResolution.values()) {
            for (PlanExecutionContextLeaseDisposition disposition
                    : DISPOSITIONS) {
                boolean legal = switch (resolution) {
                    case OBSERVED_CONFIRMED ->
                            disposition
                                    == PlanExecutionContextLeaseDisposition
                                            .NO_LEASE_ACTION;
                    case CONFIRM_APPLIED, CONFIRM_REPLAYED,
                            RECONCILED_AFTER_RESPONSE_LOSS ->
                            disposition
                                    == PlanExecutionContextLeaseDisposition
                                            .RETAINED_FOR_RECOVERY;
                    case OBSERVED_CONCURRENT_CONFIRMATION ->
                            disposition
                                    != PlanExecutionContextLeaseDisposition
                                            .NO_LEASE_ACTION;
                };
                assertOutcome(
                        legal,
                        () -> new PlanExecutionContextReady(
                                resolution,
                                persisted,
                                verified,
                                disposition),
                        "planExecutionContextReady.leaseDisposition");
            }
        }

        assertInvalidOutcome(
                () -> new PlanExecutionContextReady(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        persisted,
                        verifiedWorkspace(spec("wrong-spec"), FINGERPRINT),
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextReady.verifiedWorkspace");
        assertInvalidOutcome(
                () -> new PlanExecutionContextReady(
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONFIRMED,
                        persisted,
                        verifiedWorkspace(exactSpec, WRONG_FINGERPRINT),
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION),
                "planExecutionContextReady.verifiedWorkspace");
    }

    @Test
    void advancedStageFailureDispositionComplementIsExact() {
        PersistenceFailure canonical = executionAdvanced();
        List<PersistenceFailure> failures = List.of(
                canonical,
                executionPartial(),
                failure(
                        PersistenceErrorCode
                                .EXECUTION_RECOVERY_ADVANCED_STATE,
                        "executionRecovery." + SECRET));
        for (PlanExecutionContextCompositionStage stage : STAGES) {
            for (PersistenceFailure candidateFailure : failures) {
                for (PlanExecutionContextLeaseDisposition disposition
                        : DISPOSITIONS) {
                    boolean canonicalFailure =
                            candidateFailure.equals(canonical);
                    boolean legalStage =
                            stage == PlanExecutionContextCompositionStage
                                            .INITIAL_EXECUTION_START_INSPECT
                                    || stage
                                            == PlanExecutionContextCompositionStage
                                                    .POST_LEASE_EXECUTION_START_INSPECT;
                    boolean legalDisposition =
                            stage == PlanExecutionContextCompositionStage
                                            .INITIAL_EXECUTION_START_INSPECT
                                    ? disposition
                                            == PlanExecutionContextLeaseDisposition
                                                    .NO_LEASE_ACTION
                                    : disposition
                                            != PlanExecutionContextLeaseDisposition
                                                    .NO_LEASE_ACTION;
                    boolean legal = canonicalFailure
                            && legalStage
                            && legalDisposition;
                    String path = !canonicalFailure
                            ? "planExecutionContextAdvancedUnsupported.failure"
                            : !legalStage
                                    ? "planExecutionContextAdvancedUnsupported"
                                            + ".stage"
                                    : "planExecutionContextAdvancedUnsupported"
                                            + ".leaseDisposition";
                    assertOutcome(
                            legal,
                            () -> new PlanExecutionContextAdvancedUnsupported(
                                    PLAN_ID,
                                    stage,
                                    candidateFailure,
                                    disposition),
                            path);
                }
            }
        }
    }

    @Test
    void retryFourByTwelveByFourComplementIsExact() {
        for (PlanExecutionContextRetryReason reason
                : PlanExecutionContextRetryReason.values()) {
            PlanExecutionContextCompositionStage expectedStage =
                    expectedRetryStage(reason);
            PlanExecutionContextLeaseDisposition expectedDisposition =
                    reason == PlanExecutionContextRetryReason
                                    .EXECUTION_START_NOT_COMMITTED
                            ? PlanExecutionContextLeaseDisposition
                                    .NO_LEASE_ACTION
                            : PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY;
            for (PlanExecutionContextCompositionStage stage : STAGES) {
                for (PlanExecutionContextLeaseDisposition disposition
                        : DISPOSITIONS) {
                    boolean legal = stage == expectedStage
                            && disposition == expectedDisposition;
                    String path = stage != expectedStage
                            ? "planExecutionContextRetryRequired.stage"
                            : "planExecutionContextRetryRequired"
                                    + ".leaseDisposition";
                    assertOutcome(
                            legal,
                            () -> new PlanExecutionContextRetryRequired(
                                    PLAN_ID,
                                    stage,
                                    reason,
                                    disposition),
                            path);
                }
            }
        }
    }

    @Test
    void persistenceNineStageFamiliesAndCompleteComplementAreExact() {
        Map<PlanExecutionContextCompositionStage, List<PersistenceFailure>>
                canonicalByStage = canonicalPersistenceFailures();
        for (PlanExecutionContextCompositionStage stage : STAGES) {
            List<PersistenceFailure> canonicalFailures =
                    canonicalByStage.get(stage);
            if (canonicalFailures == null) {
                for (PlanExecutionContextLeaseDisposition disposition
                        : DISPOSITIONS) {
                    assertInvalidOutcome(
                            () -> new PlanExecutionContextPersistenceRejected(
                                    PLAN_ID,
                                    stage,
                                    executionNotFound(),
                                    disposition),
                            "planExecutionContextPersistenceRejected.stage");
                }
                continue;
            }

            Set<PlanExecutionContextLeaseDisposition> allowedDispositions =
                    persistenceDispositions(stage);
            for (PersistenceFailure canonical : canonicalFailures) {
                for (PlanExecutionContextLeaseDisposition disposition
                        : DISPOSITIONS) {
                    assertOutcome(
                            allowedDispositions.contains(disposition),
                            () -> new PlanExecutionContextPersistenceRejected(
                                    PLAN_ID,
                                    stage,
                                    canonical,
                                    disposition),
                            "planExecutionContextPersistenceRejected"
                                    + ".leaseDisposition");
                }
            }

            for (PersistenceFailure canonical : canonicalFailures) {
                List<PersistenceFailure> wrongFailures = List.of(
                        failure(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_ADVANCED_STATE,
                                canonical.path()),
                        failure(
                                canonical.code(),
                                canonical.path() + ".wrong"),
                        wrongPersistenceFamily(stage),
                        secretNonCanonicalFailure());
                for (PersistenceFailure wrong : wrongFailures) {
                    for (PlanExecutionContextLeaseDisposition disposition
                            : DISPOSITIONS) {
                        assertInvalidOutcome(
                                () -> new
                                        PlanExecutionContextPersistenceRejected(
                                                PLAN_ID,
                                                stage,
                                                wrong,
                                                disposition),
                                "planExecutionContextPersistenceRejected"
                                        + ".failure");
                    }
                }
            }
        }
    }

    @Test
    void workspaceTwelveByAllCodesByFourComplementIsExact() {
        for (PlanExecutionContextCompositionStage stage : STAGES) {
            for (WorkspaceErrorCode code : WorkspaceErrorCode.values()) {
                for (PlanExecutionContextLeaseDisposition disposition
                        : DISPOSITIONS) {
                    boolean legalStage =
                            stage == PlanExecutionContextCompositionStage
                                            .WORKSPACE_INSPECT
                                    || stage
                                            == PlanExecutionContextCompositionStage
                                                    .WORKSPACE_MATERIALIZE
                                    || stage
                                            == PlanExecutionContextCompositionStage
                                                    .POST_MATERIALIZE_WORKSPACE_INSPECT;
                    boolean legalCode = switch (stage) {
                        case WORKSPACE_INSPECT ->
                                INSPECT_CODES.contains(code);
                        case WORKSPACE_MATERIALIZE ->
                                MATERIALIZE_CODES.contains(code);
                        case POST_MATERIALIZE_WORKSPACE_INSPECT ->
                                POST_MATERIALIZE_CODES.contains(code);
                        default -> false;
                    };
                    boolean legalDisposition =
                            stage == PlanExecutionContextCompositionStage
                                            .WORKSPACE_INSPECT
                                    || disposition
                                            == PlanExecutionContextLeaseDisposition
                                                    .RETAINED_FOR_RECOVERY;
                    boolean legal =
                            legalStage && legalCode && legalDisposition;
                    String path = !legalStage
                            ? "planExecutionContextWorkspaceRejected.stage"
                            : !legalCode
                                    ? "planExecutionContextWorkspaceRejected"
                                            + ".workspaceErrorCode"
                                    : "planExecutionContextWorkspaceRejected"
                                            + ".leaseDisposition";
                    assertOutcome(
                            legal,
                            () -> new PlanExecutionContextWorkspaceRejected(
                                    PLAN_ID,
                                    stage,
                                    code,
                                    disposition),
                            path);
                }
            }
        }
    }

    @Test
    void validationAndProtocolPathLexiconsAreExact() {
        Set<String> validationPaths = Set.of(
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
        validationPaths.forEach(path -> assertEquals(
                path,
                PlanExecutionContextCompositionValues.validationPath(path)));
        for (String path : List.of(
                "",
                "planExecutionContextComposition",
                "planExecutionContextReady.extra",
                "adapter." + SECRET)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PlanExecutionContextCompositionValues
                            .validationPath(path));
        }

        Set<String> protocolBases = Set.of(
                "planExecutionContextComposition"
                        + ".initialExecutionStartInspectResult",
                "planExecutionContextComposition"
                        + ".initialContextInspectResult",
                "planExecutionContextComposition.leaseAcquireResult",
                "planExecutionContextComposition"
                        + ".postLeaseExecutionStartInspectResult",
                "planExecutionContextComposition"
                        + ".postLeaseContextInspectResult",
                "planExecutionContextComposition.reserveResult",
                "planExecutionContextComposition"
                        + ".postReserveContextInspectResult",
                "planExecutionContextComposition.workspaceInspectResult",
                "planExecutionContextComposition"
                        + ".workspaceMaterializeResult",
                "planExecutionContextComposition"
                        + ".postMaterializeWorkspaceInspectResult",
                "planExecutionContextComposition.confirmResult",
                "planExecutionContextComposition"
                        + ".postConfirmContextInspectResult");
        for (String base : protocolBases) {
            for (String suffix : List.of("", ".outcome", ".value", ".failure")) {
                String path = base + suffix;
                assertEquals(
                        path,
                        PlanExecutionContextCompositionValues.protocolPath(
                                path));
            }
        }
        for (String path : List.of(
                "",
                "planExecutionContextComposition",
                "planExecutionContextComposition.leaseAcquireResult.extra",
                "adapter." + SECRET)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PlanExecutionContextCompositionValues.protocolPath(
                            path));
        }
    }

    @Test
    void capturedInvocationAndFourScriptedPortsAreDeterministic() {
        IllegalStateException failure =
                new IllegalStateException("scripted-" + SECRET);
        DefaultPlanExecutionContextComposer.Captured capturedValue =
                DefaultPlanExecutionContextComposer.capture(() -> "value");
        assertEquals("value", capturedValue.result());
        assertNull(capturedValue.exception());
        DefaultPlanExecutionContextComposer.Captured capturedFailure =
                DefaultPlanExecutionContextComposer.capture(() -> {
                    throw failure;
                });
        assertNull(capturedFailure.result());
        assertSame(failure, capturedFailure.exception());

        List<String> trace = new ArrayList<>();
        var execution =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedExecutionStartRecoveryRepository(
                                List.of(
                                        PersistenceResult.rejected(
                                                PersistenceErrorCode.NOT_FOUND,
                                                "planId"),
                                        NULL,
                                        failure),
                                trace);
        assertEquals(
                PersistenceErrorCode.NOT_FOUND,
                execution.inspect(PLAN_ID)
                        .failure()
                        .orElseThrow()
                        .code());
        assertNull(execution.inspect(PLAN_ID));
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> execution.inspect(PLAN_ID)));

        var contexts =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedPlanExecutionContextRepository(
                                List.of(NULL),
                                List.of(NULL),
                                List.of(NULL),
                                trace);
        assertNull(contexts.inspect(PLAN_ID));
        assertNull(contexts.reserve(null));
        assertNull(contexts.confirm(null));

        var leases =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedLeaseRepository(List.of(NULL), trace);
        assertNull(leases.acquire(
                PLAN_ID,
                "owner",
                "token",
                Instant.parse("2026-07-25T01:00:00Z")));

        var exactSpec = spec("scripted-ports");
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var workspace =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedWorkspacePort(
                                List.of(verified),
                                List.of(NULL),
                                trace);
        assertEquals(verified, workspace.inspectMaterialization(exactSpec));
        assertNull(workspace.materialize(exactSpec));

        assertEquals(3, execution.inspectCalls.get());
        assertEquals(1, contexts.inspectCalls.get());
        assertEquals(1, contexts.reserveCalls.get());
        assertEquals(1, contexts.confirmCalls.get());
        assertEquals(1, leases.acquireCalls.get());
        assertEquals(0, leases.findCalls.get());
        assertEquals(0, leases.renewCalls.get());
        assertEquals(0, leases.releaseCalls.get());
        assertEquals(1, workspace.inspectCalls.get());
        assertEquals(1, workspace.materializeCalls.get());
        assertEquals(0, workspace.unexpectedCalls.get());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "execution.inspect",
                        "execution.inspect",
                        "context.inspect",
                        "context.reserve",
                        "context.confirm",
                        "lease.acquire",
                        "workspace.inspect",
                        "workspace.materialize"),
                trace);
    }

    @Test
    void executionAndContextInspectionClassifiersAreFailClosed() {
        String executionPath = "planExecutionContextComposition"
                + ".initialExecutionStartInspectResult";
        for (var expected : List.of(
                Map.entry(
                        executionNotFound(),
                        DefaultPlanExecutionContextComposer
                                .ExecutionStartState.NOT_FOUND),
                Map.entry(
                        executionPartial(),
                        DefaultPlanExecutionContextComposer
                                .ExecutionStartState.PARTIAL),
                Map.entry(
                        executionAdvanced(),
                        DefaultPlanExecutionContextComposer
                                .ExecutionStartState.ADVANCED))) {
            var observation = DefaultPlanExecutionContextComposer
                    .classifyExecutionInspection(
                            PLAN_ID,
                            new DefaultPlanExecutionContextComposer.Captured(
                                    PersistenceResult.rejected(
                                            expected.getKey().code(),
                                            expected.getKey().path()),
                                    null),
                            PlanExecutionContextCompositionStage
                                    .INITIAL_EXECUTION_START_INSPECT,
                            executionPath,
                            PlanExecutionContextLeaseDisposition
                                    .NO_LEASE_ACTION);
            assertEquals(expected.getValue(), observation.state());
            assertEquals(expected.getKey(), observation.failure());
        }
        assertClassifierProtocol(
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_EXECUTION_START_AUTHORITY,
                executionPath + ".failure",
                () -> DefaultPlanExecutionContextComposer
                        .classifyExecutionInspection(
                                PLAN_ID,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(
                                                PersistenceResult.rejected(
                                                        PersistenceErrorCode
                                                                .NOT_FOUND,
                                                        "wrong"),
                                                null),
                                PlanExecutionContextCompositionStage
                                        .INITIAL_EXECUTION_START_INSPECT,
                                executionPath,
                                PlanExecutionContextLeaseDisposition
                                        .NO_LEASE_ACTION));

        String contextPath = "planExecutionContextComposition"
                + ".initialContextInspectResult";
        var exactSpec = spec("context-classifier");
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        List<Map.Entry<Object, DefaultPlanExecutionContextComposer
                .ContextState>> cases = List.of(
                Map.entry(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.NOT_FOUND,
                                "planExecutionContext"),
                        DefaultPlanExecutionContextComposer
                                .ContextState.NONE),
                Map.entry(
                        PersistenceResult.rejected(
                                PersistenceErrorCode
                                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                                "planExecutionContext"),
                        DefaultPlanExecutionContextComposer
                                .ContextState.PARTIAL),
                Map.entry(
                        PersistenceResult.found(confirmed.reservation()),
                        DefaultPlanExecutionContextComposer
                                .ContextState.RESERVED),
                Map.entry(
                        PersistenceResult.found(confirmed),
                        DefaultPlanExecutionContextComposer
                                .ContextState.CONFIRMED));
        for (var expected : cases) {
            var observation = DefaultPlanExecutionContextComposer
                    .classifyContextInspection(
                            PLAN_ID,
                            new DefaultPlanExecutionContextComposer.Captured(
                                    expected.getKey(),
                                    null),
                            PlanExecutionContextCompositionStage
                                    .INITIAL_CONTEXT_INSPECT,
                            contextPath,
                            PlanExecutionContextLeaseDisposition
                                    .NO_LEASE_ACTION);
            assertEquals(expected.getValue(), observation.state());
        }
        assertClassifierProtocol(
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                contextPath + ".outcome",
                () -> DefaultPlanExecutionContextComposer
                        .classifyContextInspection(
                                PLAN_ID,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(
                                                PersistenceResult.applied(
                                                        confirmed),
                                                null),
                                PlanExecutionContextCompositionStage
                                        .INITIAL_CONTEXT_INSPECT,
                                contextPath,
                                PlanExecutionContextLeaseDisposition
                                        .NO_LEASE_ACTION));
    }

    @Test
    void persistenceMutationClassifiersPreserveOnlyCanonicalAuthority() {
        Instant acquiredAt = Instant.parse("2026-07-25T00:00:00Z");
        Instant expiresAt = acquiredAt.plusSeconds(60);
        var attempt = new PlanExecutionContextLeaseAttempt(
                "owner",
                "token",
                expiresAt);
        var lease = new LeaseRecord(
                PLAN_ID,
                "owner",
                "token",
                9,
                acquiredAt,
                expiresAt);
        var applied = DefaultPlanExecutionContextComposer
                .classifyLeaseResult(
                        PLAN_ID,
                        attempt,
                        new DefaultPlanExecutionContextComposer.Captured(
                                PersistenceResult.applied(lease),
                                null),
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.APPLIED,
                applied.state());
        assertEquals(lease, applied.value());

        var rejected = DefaultPlanExecutionContextComposer
                .classifyLeaseResult(
                        PLAN_ID,
                        attempt,
                        new DefaultPlanExecutionContextComposer.Captured(
                                PersistenceResult.rejected(
                                        PersistenceErrorCode.LEASE_HELD,
                                        "planId"),
                                null),
                        PlanExecutionContextLeaseDisposition.NOT_ACQUIRED);
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.REJECTED,
                rejected.state());
        assertClassifierProtocol(
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_LEASE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".leaseAcquireResult.value",
                () -> DefaultPlanExecutionContextComposer.classifyLeaseResult(
                        PLAN_ID,
                        attempt,
                        new DefaultPlanExecutionContextComposer.Captured(
                                PersistenceResult.applied(new LeaseRecord(
                                        PLAN_ID,
                                        "wrong-owner",
                                        "token",
                                        10,
                                        acquiredAt,
                                        expiresAt)),
                                null),
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY));

        var exactSpec = spec("mutation-classifier");
        var expectedConfirmed = persistedContext(exactSpec, FINGERPRINT);
        var expectedReserved = expectedConfirmed.reservation();
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.REPLAYED,
                DefaultPlanExecutionContextComposer.classifyReserveResult(
                                PLAN_ID,
                                expectedReserved,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(
                                                PersistenceResult.replayed(
                                                        expectedReserved),
                                                null))
                        .state());
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.APPLIED,
                DefaultPlanExecutionContextComposer.classifyConfirmResult(
                                PLAN_ID,
                                expectedConfirmed,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(
                                                PersistenceResult.applied(
                                                        expectedConfirmed),
                                                null))
                        .state());
        RuntimeException thrown =
                new IllegalStateException("response-loss-" + SECRET);
        assertSame(
                thrown,
                DefaultPlanExecutionContextComposer.classifyConfirmResult(
                                PLAN_ID,
                                expectedConfirmed,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(null, thrown))
                        .exception());
    }

    @Test
    void workspaceClassifiersBindCodeOperationAndProjectPath() {
        var exactSpec = spec("workspace-classifier");
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var active = DefaultPlanExecutionContextComposer
                .classifyWorkspaceInspection(
                        PLAN_ID,
                        exactSpec,
                        new DefaultPlanExecutionContextComposer.Captured(
                                verified,
                                null),
                        PlanExecutionContextCompositionStage
                                .WORKSPACE_INSPECT,
                        "planExecutionContextComposition"
                                + ".workspaceInspectResult",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        assertEquals(
                DefaultPlanExecutionContextComposer.WorkspaceState.VERIFIED,
                active.state());

        var notFound = DefaultPlanExecutionContextComposer
                .classifyWorkspaceInspection(
                        PLAN_ID,
                        exactSpec,
                        new DefaultPlanExecutionContextComposer.Captured(
                                null,
                                new WorkspaceException(
                                        WorkspaceErrorCode
                                                .WORKSPACE_NOT_FOUND,
                                        "inspectMaterialization")),
                        PlanExecutionContextCompositionStage
                                .WORKSPACE_INSPECT,
                        "planExecutionContextComposition"
                                + ".workspaceInspectResult",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        assertEquals(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                notFound.errorCode());
        assertClassifierProtocol(
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceInspectResult.failure",
                () -> DefaultPlanExecutionContextComposer
                        .classifyWorkspaceInspection(
                                PLAN_ID,
                                exactSpec,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(
                                                null,
                                                new WorkspaceException(
                                                        WorkspaceErrorCode
                                                                .WORKSPACE_NOT_FOUND,
                                                        "materialize",
                                                        new ProjectPath(
                                                                "secret.txt"))),
                                PlanExecutionContextCompositionStage
                                        .WORKSPACE_INSPECT,
                                "planExecutionContextComposition"
                                        + ".workspaceInspectResult",
                                PlanExecutionContextLeaseDisposition
                                        .RETAINED_FOR_RECOVERY));

        var materializeRejected = DefaultPlanExecutionContextComposer
                .classifyWorkspaceMaterialization(
                        PLAN_ID,
                        exactSpec,
                        new DefaultPlanExecutionContextComposer.Captured(
                                null,
                                new WorkspaceException(
                                        WorkspaceErrorCode.IO_FAILURE,
                                        "materialize",
                                        new ProjectPath("opaque.txt"))));
        assertEquals(
                WorkspaceErrorCode.IO_FAILURE,
                materializeRejected.errorCode());
        assertEquals(
                DefaultPlanExecutionContextComposer.WorkspaceState.NULL_RESULT,
                DefaultPlanExecutionContextComposer
                        .classifyWorkspaceMaterialization(
                                PLAN_ID,
                                exactSpec,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(null, null))
                        .state());
        RuntimeException generic =
                new IllegalStateException("workspace-" + SECRET);
        assertSame(
                generic,
                DefaultPlanExecutionContextComposer
                        .classifyWorkspaceMaterialization(
                                PLAN_ID,
                                exactSpec,
                                new DefaultPlanExecutionContextComposer
                                        .Captured(null, generic))
                        .exception());
    }

    @Test
    void inspectionClassifierMatrixSeparatesNullWrongContainerAndAuthority() {
        String executionPath = "planExecutionContextComposition"
                + ".initialExecutionStartInspectResult";
        PlanExecutionContextCompositionStage executionStage =
                PlanExecutionContextCompositionStage
                        .INITIAL_EXECUTION_START_INSPECT;
        PlanExecutionContextLeaseDisposition disposition =
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION;
        assertProtocolSanitized(
                executionStage,
                PlanExecutionContextCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                executionPath,
                disposition,
                null,
                () -> classifyExecution(NULL_CAPTURE, executionPath));
        assertProtocolSanitized(
                executionStage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_EXECUTION_START_AUTHORITY,
                executionPath + ".value",
                disposition,
                null,
                () -> classifyExecution(
                        captured("wrong-" + SECRET),
                        executionPath));
        RuntimeException executionThrown =
                new IllegalStateException("execution-" + SECRET);
        assertProtocolSanitized(
                executionStage,
                PlanExecutionContextCompositionProtocolCode
                        .COLLABORATOR_EXCEPTION,
                executionPath,
                disposition,
                executionThrown,
                () -> classifyExecution(
                        thrown(executionThrown),
                        executionPath));
        var exactCommitted = committed(
                SECRET,
                Optional.of(spec("classifier").sourceProjectVersion()));
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .ExecutionStartState.COMMITTED,
                classifyExecution(
                        captured(PersistenceResult.found(exactCommitted)),
                        executionPath)
                        .state());
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .ExecutionStartState.READY,
                classifyExecution(
                        captured(PersistenceResult.found(
                                PlanExecutionContextCompositionTestFixtures
                                        .ready(
                                                SECRET,
                                                Optional.empty()))),
                        executionPath)
                        .state());
        for (PersistenceResult<?> unexpected : List.of(
                PersistenceResult.applied(exactCommitted),
                PersistenceResult.replayed(exactCommitted))) {
            assertProtocolSanitized(
                    executionStage,
                    PlanExecutionContextCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    executionPath + ".outcome",
                    disposition,
                    null,
                    () -> classifyExecution(
                            captured(unexpected),
                            executionPath));
        }
        for (Object wrongValue : List.of(
                "wrong-type-" + SECRET,
                PlanExecutionContextCompositionTestFixtures.ready(
                        "wrong-plan",
                        Optional.empty()))) {
            assertProtocolSanitized(
                    executionStage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    executionPath + ".value",
                    disposition,
                    null,
                    () -> classifyExecution(
                            captured(PersistenceResult.found(wrongValue)),
                            executionPath));
        }
        for (PersistenceFailure canonical : List.of(
                executionNotFound(),
                executionPartial(),
                executionAdvanced())) {
            assertDoesNotThrow(() -> classifyExecution(
                    captured(PersistenceResult.rejected(
                            canonical.code(),
                            canonical.path())),
                    executionPath));
        }
        for (PersistenceFailure nonCanonical : List.of(
                failure(
                        PersistenceErrorCode.NOT_FOUND,
                        "planId." + SECRET),
                failure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "planId"))) {
            assertProtocolSanitized(
                    executionStage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    executionPath + ".failure",
                    disposition,
                    null,
                    () -> classifyExecution(
                            captured(PersistenceResult.rejected(
                                    nonCanonical.code(),
                                    nonCanonical.path())),
                            executionPath));
        }

        String contextPath = "planExecutionContextComposition"
                + ".initialContextInspectResult";
        PlanExecutionContextCompositionStage contextStage =
                PlanExecutionContextCompositionStage.INITIAL_CONTEXT_INSPECT;
        assertProtocolSanitized(
                contextStage,
                PlanExecutionContextCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                contextPath,
                disposition,
                null,
                () -> classifyContext(NULL_CAPTURE, contextPath));
        assertProtocolSanitized(
                contextStage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                contextPath + ".value",
                disposition,
                null,
                () -> classifyContext(
                        captured("wrong-" + SECRET),
                        contextPath));
        RuntimeException contextThrown =
                new IllegalArgumentException("context-" + SECRET);
        assertProtocolSanitized(
                contextStage,
                PlanExecutionContextCompositionProtocolCode
                        .COLLABORATOR_EXCEPTION,
                contextPath,
                disposition,
                contextThrown,
                () -> classifyContext(thrown(contextThrown), contextPath));
        var exactConfirmed =
                persistedContext(spec("context-matrix"), FINGERPRINT);
        for (PersistenceResult<?> unexpected : List.of(
                PersistenceResult.applied(exactConfirmed),
                PersistenceResult.replayed(exactConfirmed))) {
            assertProtocolSanitized(
                    contextStage,
                    PlanExecutionContextCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    contextPath + ".outcome",
                    disposition,
                    null,
                    () -> classifyContext(
                            captured(unexpected),
                            contextPath));
        }
        var wrongPlanReserved =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                new io.paperagent.v2.contracts.PlanId(
                                        "wrong-plan"),
                                exactConfirmed.materializationSpec(),
                                "owner",
                                1);
        for (Object wrongValue : List.of(
                "wrong-type-" + SECRET,
                wrongPlanReserved)) {
            assertProtocolSanitized(
                    contextStage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    contextPath + ".value",
                    disposition,
                    null,
                    () -> classifyContext(
                            captured(PersistenceResult.found(wrongValue)),
                            contextPath));
        }
        for (PersistenceFailure canonical : List.of(
                failure(
                        PersistenceErrorCode.NOT_FOUND,
                        "planExecutionContext"),
                contextPartial())) {
            assertDoesNotThrow(() -> classifyContext(
                    captured(PersistenceResult.rejected(
                            canonical.code(),
                            canonical.path())),
                    contextPath));
        }
        for (PersistenceFailure nonCanonical : List.of(
                failure(
                        PersistenceErrorCode.NOT_FOUND,
                        "planExecutionContext." + SECRET),
                failure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "planExecutionContext"))) {
            assertProtocolSanitized(
                    contextStage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    contextPath + ".failure",
                    disposition,
                    null,
                    () -> classifyContext(
                            captured(PersistenceResult.rejected(
                                    nonCanonical.code(),
                                    nonCanonical.path())),
                            contextPath));
        }
    }

    @Test
    void scriptedActionThenThrowRunsDelegateBeforeFailureAndQueuesFailHard() {
        List<String> trace = new ArrayList<>();
        AtomicInteger actions = new AtomicInteger();
        RuntimeException executionFailure =
                new IllegalStateException("execution-" + SECRET);
        var execution =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedExecutionStartRecoveryRepository(
                                List.of(actionThenThrow(
                                        actions::incrementAndGet,
                                        executionFailure)),
                                trace);
        assertSame(
                executionFailure,
                assertThrows(
                        IllegalStateException.class,
                        () -> execution.inspect(PLAN_ID)));
        assertThrows(
                AssertionError.class,
                () -> execution.inspect(PLAN_ID));

        RuntimeException acquireFailure =
                new IllegalStateException("acquire-" + SECRET);
        var leases =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedLeaseRepository(
                                List.of(actionThenThrow(
                                        actions::incrementAndGet,
                                        acquireFailure)),
                                trace);
        assertSame(
                acquireFailure,
                assertThrows(
                        IllegalStateException.class,
                        () -> leases.acquire(
                                PLAN_ID,
                                "owner",
                                "token",
                                Instant.parse(
                                        "2026-07-25T01:00:00Z"))));
        assertThrows(
                AssertionError.class,
                () -> leases.acquire(
                        PLAN_ID,
                        "owner",
                        "token",
                        Instant.parse("2026-07-25T01:00:00Z")));

        RuntimeException reserveFailure =
                new IllegalStateException("reserve-" + SECRET);
        RuntimeException confirmFailure =
                new IllegalStateException("confirm-" + SECRET);
        var contexts =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedPlanExecutionContextRepository(
                                List.of(),
                                List.of(actionThenThrow(
                                        actions::incrementAndGet,
                                        reserveFailure)),
                                List.of(actionThenThrow(
                                        actions::incrementAndGet,
                                        confirmFailure)),
                                trace);
        assertSame(
                reserveFailure,
                assertThrows(
                        IllegalStateException.class,
                        () -> contexts.reserve(null)));
        assertSame(
                confirmFailure,
                assertThrows(
                        IllegalStateException.class,
                        () -> contexts.confirm(null)));
        assertThrows(
                AssertionError.class,
                () -> contexts.reserve(null));
        assertThrows(
                AssertionError.class,
                () -> contexts.confirm(null));

        RuntimeException materializeFailure =
                new IllegalStateException("materialize-" + SECRET);
        var workspace =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedWorkspacePort(
                                List.of(),
                                List.of(actionThenThrow(
                                        actions::incrementAndGet,
                                        materializeFailure)),
                                trace);
        assertSame(
                materializeFailure,
                assertThrows(
                        IllegalStateException.class,
                        () -> workspace.materialize(spec("delegate"))));
        assertThrows(
                AssertionError.class,
                () -> workspace.materialize(spec("delegate")));

        assertEquals(5, actions.get());
        assertEquals(2, execution.inspectCalls.get());
        assertEquals(2, leases.acquireCalls.get());
        assertEquals(2, contexts.reserveCalls.get());
        assertEquals(2, contexts.confirmCalls.get());
        assertEquals(2, workspace.materializeCalls.get());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "execution.inspect",
                        "lease.acquire",
                        "lease.acquire",
                        "context.reserve",
                        "context.confirm",
                        "context.reserve",
                        "context.confirm",
                        "workspace.materialize",
                        "workspace.materialize"),
                trace);
    }

    @Test
    void persistenceMutationClassifierMatrixIsExactAndIndependent() {
        Instant acquiredAt = Instant.parse("2026-07-25T00:00:00Z");
        Instant expiresAt = acquiredAt.plusSeconds(60);
        var attempt = new PlanExecutionContextLeaseAttempt(
                "owner",
                "token",
                expiresAt);
        var exactLease = new LeaseRecord(
                PLAN_ID,
                "owner",
                "token",
                11,
                acquiredAt,
                expiresAt);
        for (PersistenceResult<LeaseRecord> success : List.of(
                PersistenceResult.applied(exactLease),
                PersistenceResult.replayed(exactLease))) {
            var classified =
                    DefaultPlanExecutionContextComposer.classifyLeaseResult(
                            PLAN_ID,
                            attempt,
                            captured(success),
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            assertEquals(success.outcome().name(), classified.state().name());
            assertEquals(exactLease, classified.value());
        }
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.NULL_RESULT,
                DefaultPlanExecutionContextComposer.classifyLeaseResult(
                                PLAN_ID,
                                attempt,
                                NULL_CAPTURE,
                                PlanExecutionContextLeaseDisposition
                                        .ACQUISITION_INDETERMINATE)
                        .state());
        RuntimeException leaseThrown =
                new IllegalStateException("lease-" + SECRET);
        assertSame(
                leaseThrown,
                DefaultPlanExecutionContextComposer.classifyLeaseResult(
                                PLAN_ID,
                                attempt,
                                thrown(leaseThrown),
                                PlanExecutionContextLeaseDisposition
                                        .ACQUISITION_INDETERMINATE)
                        .exception());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "planExecutionContextComposition"
                        + ".leaseAcquireResult.outcome",
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyLeaseResult(
                                PLAN_ID,
                                attempt,
                                captured(PersistenceResult.found(exactLease)),
                                PlanExecutionContextLeaseDisposition
                                        .ACQUISITION_INDETERMINATE));
        List<LeaseRecord> wrongLeases = List.of(
                new LeaseRecord(
                        new io.paperagent.v2.contracts.PlanId("wrong-plan"),
                        "owner",
                        "token",
                        12,
                        acquiredAt,
                        expiresAt),
                new LeaseRecord(
                        PLAN_ID,
                        "wrong-owner",
                        "token",
                        12,
                        acquiredAt,
                        expiresAt),
                new LeaseRecord(
                        PLAN_ID,
                        "owner",
                        "wrong-token",
                        12,
                        acquiredAt,
                        expiresAt),
                new LeaseRecord(
                        PLAN_ID,
                        "owner",
                        "token",
                        12,
                        acquiredAt,
                        expiresAt.plusSeconds(1)));
        for (LeaseRecord wrong : wrongLeases) {
            for (PersistenceOutcomeFactory outcome
                    : PersistenceOutcomeFactory.values()) {
                assertProtocolSanitized(
                        PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_LEASE_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".leaseAcquireResult.value",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                        null,
                        () -> DefaultPlanExecutionContextComposer
                                .classifyLeaseResult(
                                        PLAN_ID,
                                        attempt,
                                        captured(outcome.result(wrong)),
                                        PlanExecutionContextLeaseDisposition
                                                .RETAINED_FOR_RECOVERY));
            }
        }
        for (PersistenceFailure canonical : acquireFailures()) {
            assertEquals(
                    canonical,
                    DefaultPlanExecutionContextComposer.classifyLeaseResult(
                                    PLAN_ID,
                                    attempt,
                                    captured(PersistenceResult.rejected(
                                            canonical.code(),
                                            canonical.path())),
                                    PlanExecutionContextLeaseDisposition
                                            .NOT_ACQUIRED)
                            .failure());
            assertMutationFailureRejected(
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_LEASE_AUTHORITY,
                    "planExecutionContextComposition.leaseAcquireResult",
                    PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                    () -> DefaultPlanExecutionContextComposer
                            .classifyLeaseResult(
                                    PLAN_ID,
                                    attempt,
                                    captured(PersistenceResult.rejected(
                                            canonical.code(),
                                            canonical.path() + "." + SECRET)),
                                    PlanExecutionContextLeaseDisposition
                                            .NOT_ACQUIRED));
            assertMutationFailureRejected(
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_LEASE_AUTHORITY,
                    "planExecutionContextComposition.leaseAcquireResult",
                    PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                    () -> DefaultPlanExecutionContextComposer
                            .classifyLeaseResult(
                                    PLAN_ID,
                                    attempt,
                                    captured(PersistenceResult.rejected(
                                            PersistenceErrorCode
                                                    .EXECUTION_RECOVERY_ADVANCED_STATE,
                                            canonical.path())),
                                    PlanExecutionContextLeaseDisposition
                                            .NOT_ACQUIRED));
        }

        var exactConfirmed =
                persistedContext(spec("persistence-matrix"), FINGERPRINT);
        var exactReserved = exactConfirmed.reservation();
        for (PersistenceOutcomeFactory outcome
                : PersistenceOutcomeFactory.values()) {
            assertEquals(
                    outcome.name(),
                    DefaultPlanExecutionContextComposer.classifyReserveResult(
                                    PLAN_ID,
                                    exactReserved,
                                    captured(outcome.result(exactReserved)))
                            .state()
                            .name());
            assertEquals(
                    outcome.name(),
                    DefaultPlanExecutionContextComposer.classifyConfirmResult(
                                    PLAN_ID,
                                    exactConfirmed,
                                    captured(outcome.result(exactConfirmed)))
                            .state()
                            .name());
        }
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.NULL_RESULT,
                DefaultPlanExecutionContextComposer.classifyReserveResult(
                                PLAN_ID,
                                exactReserved,
                                NULL_CAPTURE)
                        .state());
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .PersistenceMutationState.NULL_RESULT,
                DefaultPlanExecutionContextComposer.classifyConfirmResult(
                                PLAN_ID,
                                exactConfirmed,
                                NULL_CAPTURE)
                        .state());
        RuntimeException reserveThrown =
                new IllegalStateException("reserve-" + SECRET);
        RuntimeException confirmThrown =
                new IllegalStateException("confirm-" + SECRET);
        assertSame(
                reserveThrown,
                DefaultPlanExecutionContextComposer.classifyReserveResult(
                                PLAN_ID,
                                exactReserved,
                                thrown(reserveThrown))
                        .exception());
        assertSame(
                confirmThrown,
                DefaultPlanExecutionContextComposer.classifyConfirmResult(
                                PLAN_ID,
                                exactConfirmed,
                                thrown(confirmThrown))
                        .exception());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.RESERVE,
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "planExecutionContextComposition.reserveResult.outcome",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyReserveResult(
                                PLAN_ID,
                                exactReserved,
                                captured(PersistenceResult.found(
                                        exactReserved))));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.CONFIRM,
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "planExecutionContextComposition.confirmResult.outcome",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyConfirmResult(
                                PLAN_ID,
                                exactConfirmed,
                                captured(PersistenceResult.found(
                                        exactConfirmed))));

        var wrongReserved = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        PLAN_ID,
                        spec("wrong-reserved"),
                        "wrong-owner",
                        99);
        var wrongConfirmed =
                persistedContext(spec("wrong-confirmed"), WRONG_FINGERPRINT);
        for (PersistenceOutcomeFactory outcome
                : PersistenceOutcomeFactory.values()) {
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage.RESERVE,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition.reserveResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> DefaultPlanExecutionContextComposer
                            .classifyReserveResult(
                                    PLAN_ID,
                                    exactReserved,
                                    captured(outcome.result(wrongReserved))));
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage.CONFIRM,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_RECONCILIATION_AUTHORITY,
                    "planExecutionContextComposition.confirmResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> DefaultPlanExecutionContextComposer
                            .classifyConfirmResult(
                                    PLAN_ID,
                                    exactConfirmed,
                                    captured(outcome.result(wrongConfirmed))));
        }
        assertCanonicalMutationFailures(
                reserveFailures(),
                PlanExecutionContextCompositionStage.RESERVE,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                "planExecutionContextComposition.reserveResult",
                failure -> DefaultPlanExecutionContextComposer
                        .classifyReserveResult(
                                PLAN_ID,
                                exactReserved,
                                captured(PersistenceResult.rejected(
                                        failure.code(),
                                        failure.path()))));
        assertCanonicalMutationFailures(
                confirmFailures(),
                PlanExecutionContextCompositionStage.CONFIRM,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                "planExecutionContextComposition.confirmResult",
                failure -> DefaultPlanExecutionContextComposer
                        .classifyConfirmResult(
                                PLAN_ID,
                                exactConfirmed,
                                captured(PersistenceResult.rejected(
                                        failure.code(),
                        failure.path()))));
    }

    @Test
    void workspaceClassifierMatrixSeparatesEveryMalformedDimension() {
        var exactSpec = spec("workspace-matrix");
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        String inspectPath = "planExecutionContextComposition"
                + ".workspaceInspectResult";
        PlanExecutionContextCompositionStage inspectStage =
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT;
        PlanExecutionContextLeaseDisposition disposition =
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY;
        assertProtocolSanitized(
                inspectStage,
                PlanExecutionContextCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                inspectPath,
                disposition,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyWorkspaceInspection(
                                PLAN_ID,
                                exactSpec,
                                NULL_CAPTURE,
                                inspectStage,
                                inspectPath,
                                disposition));
        assertProtocolSanitized(
                inspectStage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                inspectPath + ".value",
                disposition,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyWorkspaceInspection(
                                PLAN_ID,
                                exactSpec,
                                captured("wrong-container-" + SECRET),
                                inspectStage,
                                inspectPath,
                                disposition));
        RuntimeException generic =
                new IllegalStateException("inspect-" + SECRET);
        assertProtocolSanitized(
                inspectStage,
                PlanExecutionContextCompositionProtocolCode
                        .COLLABORATOR_EXCEPTION,
                inspectPath,
                disposition,
                generic,
                () -> DefaultPlanExecutionContextComposer
                        .classifyWorkspaceInspection(
                                PLAN_ID,
                                exactSpec,
                                thrown(generic),
                                inspectStage,
                                inspectPath,
                                disposition));
        assertProtocolSanitized(
                inspectStage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                inspectPath + ".value",
                disposition,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyWorkspaceInspection(
                                PLAN_ID,
                                exactSpec,
                                captured(verifiedWorkspace(
                                        spec("mismatch"),
                                        FINGERPRINT)),
                                inspectStage,
                                inspectPath,
                                disposition));
        List<WorkspaceException> independentlyMalformed = List.of(
                new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "materialize"),
                new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "inspectMaterialization",
                        new ProjectPath("secret-" + SECRET)),
                new WorkspaceException(
                        WorkspaceErrorCode.IO_FAILURE,
                        "inspectMaterialization"));
        for (WorkspaceException malformed : independentlyMalformed) {
            assertProtocolSanitized(
                    inspectStage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_WORKSPACE_AUTHORITY,
                    inspectPath + ".failure",
                    disposition,
                    null,
                    () -> DefaultPlanExecutionContextComposer
                            .classifyWorkspaceInspection(
                                    PLAN_ID,
                                    exactSpec,
                                    thrown(malformed),
                                    inspectStage,
                                    inspectPath,
                                    disposition));
        }
        for (WorkspaceErrorCode code : INSPECT_CODES) {
            var observation = DefaultPlanExecutionContextComposer
                    .classifyWorkspaceInspection(
                            PLAN_ID,
                            exactSpec,
                            thrown(new WorkspaceException(
                                    code,
                                    "inspectMaterialization")),
                            inspectStage,
                            inspectPath,
                            disposition);
            assertEquals(
                    DefaultPlanExecutionContextComposer
                            .WorkspaceState.REJECTED,
                    observation.state());
            assertEquals(code, observation.errorCode());
        }
        assertEquals(
                verified,
                DefaultPlanExecutionContextComposer
                        .classifyWorkspaceInspection(
                                PLAN_ID,
                                exactSpec,
                                captured(verified),
                                inspectStage,
                                inspectPath,
                                disposition)
                        .verified());

        String materializePath = "planExecutionContextComposition"
                + ".workspaceMaterializeResult";
        PlanExecutionContextCompositionStage materializeStage =
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE;
        for (WorkspaceException malformed : List.of(
                new WorkspaceException(
                        WorkspaceErrorCode.IO_FAILURE,
                        "inspectMaterialization"),
                new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "materialize"))) {
            assertProtocolSanitized(
                    materializeStage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_WORKSPACE_AUTHORITY,
                    materializePath + ".failure",
                    disposition,
                    null,
                    () -> DefaultPlanExecutionContextComposer
                            .classifyWorkspaceMaterialization(
                                    PLAN_ID,
                                    exactSpec,
                                    thrown(malformed)));
        }
        assertProtocolSanitized(
                materializeStage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                materializePath + ".value",
                disposition,
                null,
                () -> DefaultPlanExecutionContextComposer
                        .classifyWorkspaceMaterialization(
                                PLAN_ID,
                                exactSpec,
                                captured(verifiedWorkspace(
                                        spec("materialize-mismatch"),
                                        FINGERPRINT))));
        assertEquals(
                DefaultPlanExecutionContextComposer
                        .WorkspaceState.NULL_RESULT,
                DefaultPlanExecutionContextComposer
                        .classifyWorkspaceMaterialization(
                                PLAN_ID,
                                exactSpec,
                                NULL_CAPTURE)
                        .state());
        RuntimeException materializeGeneric =
                new IllegalStateException("materialize-" + SECRET);
        var thrownObservation = DefaultPlanExecutionContextComposer
                .classifyWorkspaceMaterialization(
                        PLAN_ID,
                        exactSpec,
                        thrown(materializeGeneric));
        assertEquals(
                DefaultPlanExecutionContextComposer.WorkspaceState.THROWN,
                thrownObservation.state());
        assertSame(materializeGeneric, thrownObservation.exception());
        for (WorkspaceErrorCode code : MATERIALIZE_CODES) {
            var observation = DefaultPlanExecutionContextComposer
                    .classifyWorkspaceMaterialization(
                            PLAN_ID,
                            exactSpec,
                            thrown(new WorkspaceException(
                                    code,
                                    "materialize",
                                    new ProjectPath("allowed.txt"))));
            assertEquals(
                    DefaultPlanExecutionContextComposer
                            .WorkspaceState.REJECTED,
                    observation.state());
            assertEquals(code, observation.errorCode());
        }
    }

    @Test
    void composeInitialExecutionStatesStopBeforeContext() {
        var ready = PlanExecutionContextCompositionTestFixtures.ready(
                SECRET,
                Optional.empty());
        List<Map.Entry<Object, Class<?>>> cases = List.of(
                Map.entry(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.NOT_FOUND,
                                "planId"),
                        PlanExecutionContextPersistenceRejected.class),
                Map.entry(
                        PersistenceResult.rejected(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_PARTIAL_STATE,
                                "executionRecovery"),
                        PlanExecutionContextPersistenceRejected.class),
                Map.entry(
                        PersistenceResult.rejected(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_ADVANCED_STATE,
                                "executionRecovery"),
                        PlanExecutionContextAdvancedUnsupported.class),
                Map.entry(
                        PersistenceResult.found(ready),
                        PlanExecutionContextRetryRequired.class));
        for (var testCase : cases) {
            ComposeHarness harness = harness(
                    script(testCase.getKey()),
                    script(),
                    script(),
                    script());
            Object outcome = harness.composer().compose(
                    emptyRequest(PLAN_ID));
            assertEquals(testCase.getValue(), outcome.getClass());
            assertEquals(List.of("execution.inspect"), harness.trace());
            assertZeroAfterExecution(harness);
        }
    }

    @Test
    void composeSourceLessClassifiesContextBeforeSuppliedInput() {
        var sourceLess = committed(SECRET, Optional.empty());
        ComposeHarness clean = harness(
                script(PersistenceResult.found(sourceLess)),
                script(contextNone()),
                script(),
                script());
        var outcome = assertInstanceOf(
                PlanExecutionContextNotRequired.class,
                clean.composer().compose(emptyRequest(PLAN_ID)));
        assertEquals(
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                outcome.leaseDisposition());
        assertEquals(
                List.of("execution.inspect", "context.inspect"),
                clean.trace());
        assertZeroMutationsAndWorkspace(clean);

        for (PlanExecutionContextCompositionRequest supplied : List.of(
                new PlanExecutionContextCompositionRequest(
                        PLAN_ID,
                        Optional.of(spec("source-less-supplied")),
                        Optional.empty()),
                new PlanExecutionContextCompositionRequest(
                        PLAN_ID,
                        Optional.empty(),
                        Optional.of(attempt())))) {
            ComposeHarness invalid = harness(
                    script(PersistenceResult.found(sourceLess)),
                    script(contextNone()),
                    script(),
                    script());
            PlanExecutionContextCompositionValidationException failure =
                    assertThrows(
                            PlanExecutionContextCompositionValidationException
                                    .class,
                            () -> invalid.composer().compose(supplied));
            assertEquals(
                    PlanExecutionContextCompositionValidationCode
                            .INCONSISTENT_REQUEST_AUTHORITY,
                    failure.code());
            assertEquals(
                    supplied.proposedMaterializationSpec().isPresent()
                            ? "planExecutionContextComposition.request"
                                    + ".proposedMaterializationSpec"
                            : "planExecutionContextComposition.request"
                                    + ".leaseAttempt",
                    failure.path());
            assertEquals(
                    List.of("execution.inspect", "context.inspect"),
                    invalid.trace());
            assertZeroMutationsAndWorkspace(invalid);
        }

        var occupied = persistedContext(
                spec("source-less-occupied"),
                FINGERPRINT);
        for (Object context : List.of(
                PersistenceResult.found(occupied.reservation()),
                PersistenceResult.found(occupied))) {
            ComposeHarness corrupt = harness(
                    script(PersistenceResult.found(sourceLess)),
                    script(context),
                    script(),
                    script());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .INITIAL_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".initialContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                    null,
                    () -> corrupt.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.of(spec("ignored-supplied")),
                                    Optional.of(attempt()))));
            assertEquals(
                    List.of("execution.inspect", "context.inspect"),
                    corrupt.trace());
            assertZeroMutationsAndWorkspace(corrupt);
        }

        ComposeHarness partial = harness(
                script(PersistenceResult.found(sourceLess)),
                script(PersistenceResult.rejected(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                        "planExecutionContext")),
                script(),
                script());
        assertInstanceOf(
                PlanExecutionContextPersistenceRejected.class,
                partial.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(spec("ignored")),
                                Optional.of(attempt()))));
        assertZeroMutationsAndWorkspace(partial);
    }

    @Test
    void composeSourceBackedValidationOccursAfterContextBeforeSideEffects() {
        var exactSpec = spec("source-backed-validation");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        List<ValidationCase> cases = List.of(
                new ValidationCase(
                        contextNone(),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(attempt())),
                        PlanExecutionContextCompositionValidationCode
                                .REQUIRED_VALUE_MISSING,
                        "planExecutionContextComposition.request"
                                + ".proposedMaterializationSpec"),
                new ValidationCase(
                        contextNone(),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.empty()),
                        PlanExecutionContextCompositionValidationCode
                                .REQUIRED_VALUE_MISSING,
                        "planExecutionContextComposition.request"
                                + ".leaseAttempt"),
                new ValidationCase(
                        contextNone(),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(spec("wrong-source")),
                                Optional.of(attempt())),
                        PlanExecutionContextCompositionValidationCode
                                .INCONSISTENT_REQUEST_AUTHORITY,
                        "planExecutionContextComposition.request"
                                + ".proposedMaterializationSpec"),
                new ValidationCase(
                        PersistenceResult.found(confirmed.reservation()),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.empty()),
                        PlanExecutionContextCompositionValidationCode
                                .REQUIRED_VALUE_MISSING,
                        "planExecutionContextComposition.request"
                                + ".leaseAttempt"),
                new ValidationCase(
                        PersistenceResult.found(confirmed.reservation()),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(spec("changed-reserved")),
                                Optional.of(attempt())),
                        PlanExecutionContextCompositionValidationCode
                                .INCONSISTENT_REQUEST_AUTHORITY,
                        "planExecutionContextComposition.request"
                                + ".proposedMaterializationSpec"),
                new ValidationCase(
                        PersistenceResult.found(confirmed),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(attempt())),
                        PlanExecutionContextCompositionValidationCode
                                .INCONSISTENT_REQUEST_AUTHORITY,
                        "planExecutionContextComposition.request"
                                + ".leaseAttempt"),
                new ValidationCase(
                        PersistenceResult.found(confirmed),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(spec("changed-confirmed")),
                                Optional.empty()),
                        PlanExecutionContextCompositionValidationCode
                                .INCONSISTENT_REQUEST_AUTHORITY,
                        "planExecutionContextComposition.request"
                                + ".proposedMaterializationSpec"));
        for (ValidationCase testCase : cases) {
            ComposeHarness harness = harness(
                    script(PersistenceResult.found(sourceBacked)),
                    script(testCase.contextResult()),
                    script(),
                    script());
            PlanExecutionContextCompositionValidationException failure =
                    assertThrows(
                            PlanExecutionContextCompositionValidationException
                                    .class,
                            () -> harness.composer().compose(
                                    testCase.request()));
            assertEquals(testCase.code(), failure.code());
            assertEquals(testCase.path(), failure.path());
            assertEquals(
                    List.of("execution.inspect", "context.inspect"),
                    harness.trace());
            assertZeroMutationsAndWorkspace(harness);
        }
    }

    @Test
    void composeInitialConfirmedOnlyInspectsExactWorkspace() {
        var exactSpec = spec("initial-confirmed");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        ComposeHarness exact = harness(
                script(PersistenceResult.found(sourceBacked)),
                script(PersistenceResult.found(confirmed)),
                script(),
                script(verifiedWorkspace(exactSpec, FINGERPRINT)));
        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                exact.composer().compose(emptyRequest(PLAN_ID)));
        assertEquals(
                PlanExecutionContextCompositionResolution
                        .OBSERVED_CONFIRMED,
                ready.resolution());
        assertEquals(
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                ready.leaseDisposition());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "workspace.inspect"),
                exact.trace());
        assertEquals(0, exact.leases().acquireCalls.get());
        assertEquals(0, exact.contexts().reserveCalls.get());
        assertEquals(0, exact.contexts().confirmCalls.get());
        assertEquals(0, exact.workspace().materializeCalls.get());

        ComposeHarness mismatch = harness(
                script(PersistenceResult.found(sourceBacked)),
                script(PersistenceResult.found(confirmed)),
                script(),
                script(verifiedWorkspace(exactSpec, WRONG_FINGERPRINT)));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition.workspaceInspectResult"
                        + ".value",
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                null,
                () -> mismatch.composer().compose(emptyRequest(PLAN_ID)));
        assertEquals(0, mismatch.workspace().materializeCalls.get());

        ComposeHarness missing = harness(
                script(PersistenceResult.found(sourceBacked)),
                script(PersistenceResult.found(confirmed)),
                script(),
                script(new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "inspectMaterialization")));
        var rejected = assertInstanceOf(
                PlanExecutionContextWorkspaceRejected.class,
                missing.composer().compose(emptyRequest(PLAN_ID)));
        assertEquals(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                rejected.workspaceErrorCode());
        assertEquals(
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                rejected.stage());
        assertEquals(
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                rejected.leaseDisposition());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "workspace.inspect"),
                missing.trace());
        assertEquals(0, missing.leases().acquireCalls.get());
        assertEquals(0, missing.contexts().reserveCalls.get());
        assertEquals(0, missing.contexts().confirmCalls.get());
        assertEquals(0, missing.workspace().materializeCalls.get());
    }

    @Test
    void composeExactAcquireReachesOnlyTheUnconfirmedContinuationSeam() {
        var exactSpec = spec("continuation");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 17);
        var reserved =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                exactLease.ownerId(),
                                exactLease.fencingToken());
        var confirmed = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        List<ContinuationCase> cases = List.of(
                new ContinuationCase(
                        contextNone(),
                        PersistenceResult.found(reserved),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))),
                new ContinuationCase(
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(reserved),
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(leaseAttempt))));
        for (ContinuationCase testCase : cases) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(sourceBacked),
                            PersistenceResult.found(sourceBacked)),
                    script(
                            testCase.initialContext(),
                            testCase.postContext(),
                            PersistenceResult.found(confirmed)),
                    script(PersistenceResult.applied(exactLease)),
                    script(),
                    script(verifiedWorkspace(
                            exactSpec,
                            FINGERPRINT)),
                    script(),
                    script(PersistenceResult.applied(confirmed)));
            var ready = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    harness.composer().compose(testCase.request()));
            assertEquals(
                    PlanExecutionContextCompositionResolution
                            .CONFIRM_APPLIED,
                    ready.resolution());
            assertSame(confirmed, ready.persistedContext());
            assertEquals(
                    List.of(
                            "execution.inspect",
                            "context.inspect",
                            "lease.acquire",
                            "execution.inspect",
                            "context.inspect",
                            "workspace.inspect",
                            "context.confirm",
                            "context.inspect"),
                    harness.trace());
            assertEquals(1, harness.leases().acquireCalls.get());
            assertEquals(2, harness.execution().inspectCalls.get());
            assertEquals(3, harness.contexts().inspectCalls.get());
            assertEquals(0, harness.contexts().reserveCalls.get());
            assertEquals(1, harness.contexts().confirmCalls.get());
            assertEquals(1, harness.workspace().inspectCalls.get());
            assertEquals(0, harness.workspace().materializeCalls.get());
        }
    }

    @Test
    void composePostAcquireConfirmedMapsEveryLegalAcquireObservation() {
        var exactSpec = spec("post-acquire-confirmed");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 19);
        RuntimeException responseLoss =
                new IllegalStateException("acquire-" + SECRET);
        List<AcquireCase> cases = List.of(
                new AcquireCase(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.LEASE_HELD,
                                "planId"),
                        PlanExecutionContextLeaseDisposition.NOT_ACQUIRED),
                new AcquireCase(
                        NULL,
                        PlanExecutionContextLeaseDisposition
                                .ACQUISITION_INDETERMINATE),
                new AcquireCase(
                        responseLoss,
                        PlanExecutionContextLeaseDisposition
                                .ACQUISITION_INDETERMINATE),
                new AcquireCase(
                        PersistenceResult.applied(exactLease),
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY),
                new AcquireCase(
                        PersistenceResult.replayed(exactLease),
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        for (AcquireCase testCase : cases) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(sourceBacked),
                            PersistenceResult.found(sourceBacked)),
                    script(
                            PersistenceResult.found(
                                    confirmed.reservation()),
                            PersistenceResult.found(confirmed)),
                    script(testCase.acquireResult()),
                    script(verifiedWorkspace(exactSpec, FINGERPRINT)));
            var ready = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.empty(),
                                    Optional.of(leaseAttempt))));
            assertEquals(
                    PlanExecutionContextCompositionResolution
                            .OBSERVED_CONCURRENT_CONFIRMATION,
                    ready.resolution());
            assertEquals(
                    testCase.disposition(),
                    ready.leaseDisposition());
            assertEquals(
                    List.of(
                            "execution.inspect",
                            "context.inspect",
                            "lease.acquire",
                            "execution.inspect",
                            "context.inspect",
                            "workspace.inspect"),
                    harness.trace());
            assertEquals(0, harness.contexts().reserveCalls.get());
            assertEquals(0, harness.contexts().confirmCalls.get());
            assertEquals(0, harness.workspace().materializeCalls.get());
        }
    }

    @Test
    void composeAcquireCaptureIsUnconditionalAndClassificationPrecedenceExact() {
        var exactSpec = spec("acquire-precedence");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 23);
        RuntimeException firstFailure =
                new FirstCaptureException("first-" + SECRET);
        RuntimeException secondFailure =
                new SecondCaptureException("second-" + SECRET);
        AtomicInteger secondInvocation = new AtomicInteger();
        ComposeHarness bothThrow = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        firstFailure),
                script(
                        contextNone(),
                        actionThenThrow(
                                secondInvocation::incrementAndGet,
                                secondFailure)),
                script(PersistenceResult.found(exactLease)),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage
                        .POST_LEASE_EXECUTION_START_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .COLLABORATOR_EXCEPTION,
                "planExecutionContextComposition"
                        + ".postLeaseExecutionStartInspectResult",
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                firstFailure,
                () -> bothThrow.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));
        assertEquals(1, secondInvocation.get());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "lease.acquire",
                        "execution.inspect",
                        "context.inspect"),
                bothThrow.trace());

        ComposeHarness contextBeatsAcquire = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(contextNone(), secondFailure),
                script(PersistenceResult.found(exactLease)),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage
                        .POST_LEASE_CONTEXT_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .COLLABORATOR_EXCEPTION,
                "planExecutionContextComposition"
                        + ".postLeaseContextInspectResult",
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                secondFailure,
                () -> contextBeatsAcquire.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));

        ComposeHarness malformedAcquireBeatsTypedExecution = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.rejected(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_PARTIAL_STATE,
                                "executionRecovery")),
                script(contextNone(), contextNone()),
                script(PersistenceResult.found(exactLease)),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "planExecutionContextComposition"
                        + ".leaseAcquireResult.outcome",
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                null,
                () -> malformedAcquireBeatsTypedExecution
                        .composer()
                        .compose(new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));

        ComposeHarness typedExecutionAfterValidAcquire = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.rejected(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_PARTIAL_STATE,
                                "executionRecovery")),
                script(contextNone(), contextNone()),
                script(PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD,
                        "planId")),
                script());
        var rejected = assertInstanceOf(
                PlanExecutionContextPersistenceRejected.class,
                typedExecutionAfterValidAcquire
                        .composer()
                        .compose(new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));
        assertEquals(
                PlanExecutionContextCompositionStage
                        .POST_LEASE_EXECUTION_START_INSPECT,
                rejected.stage());
        assertEquals(
                PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                rejected.leaseDisposition());
    }

    @Test
    void composeSemanticExecutionCorruptionBeatsMalformedAcquire() {
        var exactSpec = spec("semantic-execution-precedence");
        var initialCommitted = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var postReady =
                new io.paperagent.v2.persistence.PersistedExecutionStartReady(
                        initialCommitted.bootstrap(),
                        initialCommitted.currentPlan());
        var initialStart = initialCommitted.executionStart();
        var changedStart =
                new io.paperagent.v2.persistence.PersistedExecutionStart(
                        initialStart.planId(),
                        "changed-owner",
                        initialStart.fencingToken() + 1,
                        initialStart.startEvent(),
                        initialStart.startedCheckpoint());
        var changedCommitted =
                new io.paperagent.v2.persistence
                        .PersistedExecutionStartCommitted(
                                initialCommitted.bootstrap(),
                                initialCommitted.currentPlan(),
                                changedStart);
        var leaseAttempt = attempt();
        var malformedAcquire = PersistenceResult.found(
                lease(leaseAttempt, 41));
        for (Object postExecution : List.of(
                PersistenceResult.found(postReady),
                PersistenceResult.found(changedCommitted))) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(initialCommitted),
                            postExecution),
                    script(contextNone(), contextNone()),
                    script(malformedAcquire),
                    script());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_LEASE_EXECUTION_START_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postLeaseExecutionStartInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null,
                    () -> harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.of(exactSpec),
                                    Optional.of(leaseAttempt))));
            assertEquals(
                    List.of(
                            "execution.inspect",
                            "context.inspect",
                            "lease.acquire",
                            "execution.inspect",
                            "context.inspect"),
                    harness.trace());
        }
    }

    @Test
    void composeSemanticExecutionCorruptionBeatsEveryMalformedContextCapture() {
        var exactSpec = spec("execution-before-context-precedence");
        var initialCommitted = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var postReady =
                new io.paperagent.v2.persistence.PersistedExecutionStartReady(
                        initialCommitted.bootstrap(),
                        initialCommitted.currentPlan());
        var initialStart = initialCommitted.executionStart();
        var changedStart =
                new io.paperagent.v2.persistence.PersistedExecutionStart(
                        initialStart.planId(),
                        "changed-owner",
                        initialStart.fencingToken() + 1,
                        initialStart.startEvent(),
                        initialStart.startedCheckpoint());
        var changedCommitted =
                new io.paperagent.v2.persistence
                        .PersistedExecutionStartCommitted(
                                initialCommitted.bootstrap(),
                                initialCommitted.currentPlan(),
                                changedStart);
        var leaseAttempt = attempt();
        RuntimeException secretContextFailure =
                new IllegalStateException("context-" + SECRET);
        List<Object> malformedContextCaptures = List.of(
                NULL,
                secretContextFailure,
                PersistenceResult.found(initialCommitted));
        for (Object postExecution : List.of(
                PersistenceResult.found(postReady),
                PersistenceResult.found(changedCommitted))) {
            for (Object postContext : malformedContextCaptures) {
                ComposeHarness harness = harness(
                        script(
                                PersistenceResult.found(initialCommitted),
                                postExecution),
                        script(contextNone(), postContext),
                        script(PersistenceResult.rejected(
                                PersistenceErrorCode.LEASE_HELD,
                                "planId")),
                        script());
                assertProtocolSanitized(
                        PlanExecutionContextCompositionStage
                                .POST_LEASE_EXECUTION_START_INSPECT,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_EXECUTION_START_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postLeaseExecutionStartInspectResult.value",
                        PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                        null,
                        () -> harness.composer().compose(
                                new PlanExecutionContextCompositionRequest(
                                        PLAN_ID,
                                        Optional.of(exactSpec),
                                        Optional.of(leaseAttempt))));
                assertEquals(2, harness.contexts().inspectCalls.get());
                assertEquals(
                        List.of(
                                "execution.inspect",
                                "context.inspect",
                                "lease.acquire",
                                "execution.inspect",
                                "context.inspect"),
                        harness.trace());
            }
        }
    }

    @Test
    void composeSemanticContextCorruptionBeatsMalformedAcquire() {
        var exactSpec = spec("semantic-context-precedence");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var initialReserved =
                persistedContext(exactSpec, FINGERPRINT).reservation();
        var changedReserved =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                "changed-owner",
                                initialReserved.fencingToken() + 1);
        var wrongConfirmation = persistedContext(
                spec("wrong-post-confirmation"),
                FINGERPRINT);
        var leaseAttempt = attempt();
        var malformedAcquire = PersistenceResult.found(
                lease(leaseAttempt, 43));
        for (Object postContext : List.of(
                contextNone(),
                PersistenceResult.found(changedReserved),
                PersistenceResult.found(wrongConfirmation))) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(sourceBacked),
                            PersistenceResult.found(sourceBacked)),
                    script(
                            PersistenceResult.found(initialReserved),
                            postContext),
                    script(malformedAcquire),
                    script());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_LEASE_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postLeaseContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null,
                    () -> harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.empty(),
                                    Optional.of(leaseAttempt))));
            assertEquals(
                    List.of(
                            "execution.inspect",
                            "context.inspect",
                            "lease.acquire",
                            "execution.inspect",
                            "context.inspect"),
                    harness.trace());
        }
    }

    @Test
    void composeMalformedAcquireBeatsEveryLegalTypedPostObservation() {
        var exactSpec = spec("typed-post-precedence");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var malformedAcquire = PersistenceResult.found(
                lease(leaseAttempt, 45));
        List<ComposeHarness> typedExecutionCases = List.of(
                harness(
                        script(
                                PersistenceResult.found(sourceBacked),
                                PersistenceResult.rejected(
                                        PersistenceErrorCode
                                                .EXECUTION_RECOVERY_PARTIAL_STATE,
                                        "executionRecovery")),
                        script(contextNone(), contextNone()),
                        script(malformedAcquire),
                        script()),
                harness(
                        script(
                                PersistenceResult.found(sourceBacked),
                                PersistenceResult.rejected(
                                        PersistenceErrorCode.NOT_FOUND,
                                        "planId")),
                        script(contextNone(), contextNone()),
                        script(malformedAcquire),
                        script()),
                harness(
                        script(
                                PersistenceResult.found(sourceBacked),
                                PersistenceResult.rejected(
                                        PersistenceErrorCode
                                                .EXECUTION_RECOVERY_ADVANCED_STATE,
                                        "executionRecovery")),
                        script(contextNone(), contextNone()),
                        script(malformedAcquire),
                        script()),
                harness(
                        script(
                                PersistenceResult.found(sourceBacked),
                                PersistenceResult.found(sourceBacked)),
                        script(
                                contextNone(),
                                PersistenceResult.rejected(
                                        PersistenceErrorCode
                                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                                        "planExecutionContext")),
                        script(malformedAcquire),
                        script()));
        for (ComposeHarness harness : typedExecutionCases) {
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    PlanExecutionContextCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "planExecutionContextComposition"
                            + ".leaseAcquireResult.outcome",
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null,
                    () -> harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.of(exactSpec),
                                    Optional.of(leaseAttempt))));
        }
    }

    @Test
    void composeMalformedAcquireNeverWashedByConfirmedAndSuccessIsNotRetained() {
        var exactSpec = spec("malformed-acquire");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 29);
        var wrongLease = new LeaseRecord(
                PLAN_ID,
                "wrong-owner",
                leaseAttempt.leaseToken(),
                30,
                Instant.parse("2026-07-25T00:00:00Z"),
                leaseAttempt.leaseExpiresAt());
        List<MalformedAcquireCase> cases = List.of(
                new MalformedAcquireCase(
                        PersistenceResult.found(exactLease),
                        PlanExecutionContextCompositionProtocolCode
                                .UNEXPECTED_PERSISTENCE_OUTCOME,
                        "planExecutionContextComposition"
                                + ".leaseAcquireResult.outcome"),
                new MalformedAcquireCase(
                        PersistenceResult.applied(wrongLease),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_LEASE_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".leaseAcquireResult.value"));
        for (MalformedAcquireCase testCase : cases) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(sourceBacked),
                            PersistenceResult.found(sourceBacked)),
                    script(
                            PersistenceResult.found(
                                    confirmed.reservation()),
                            PersistenceResult.found(confirmed)),
                    script(testCase.acquireResult()),
                    script());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    testCase.code(),
                    testCase.path(),
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null,
                    () -> harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.empty(),
                                    Optional.of(leaseAttempt))));
            assertEquals(0, harness.workspace().inspectCalls.get());
        }
    }

    @Test
    void composeNonCanonicalAcquireRejectionIsIndeterminateAndNotReconciled() {
        var exactSpec = spec("noncanonical-acquire-rejection");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        var leaseAttempt = attempt();
        for (Object acquireResult : List.of(
                PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "planId"),
                PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD,
                        SECRET))) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(sourceBacked),
                            PersistenceResult.found(sourceBacked)),
                    script(
                            PersistenceResult.found(
                                    confirmed.reservation()),
                            PersistenceResult.found(confirmed)),
                    script(acquireResult),
                    script());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_LEASE_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".leaseAcquireResult.failure",
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null,
                    () -> harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.empty(),
                                    Optional.of(leaseAttempt))));
            assertEquals(
                    List.of(
                            "execution.inspect",
                            "context.inspect",
                            "lease.acquire",
                            "execution.inspect",
                            "context.inspect"),
                    harness.trace());
            assertEquals(0, harness.workspace().inspectCalls.get());
            assertEquals(0, harness.workspace().materializeCalls.get());
            assertEquals(0, harness.contexts().reserveCalls.get());
            assertEquals(0, harness.contexts().confirmCalls.get());
        }
    }

    @Test
    void composeContinuationCapturesPostInspectionCommittedAuthority() {
        var exactSpec = spec("continuation-provenance");
        var initialCommitted = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var postCommitted =
                new io.paperagent.v2.persistence
                        .PersistedExecutionStartCommitted(
                                initialCommitted.bootstrap(),
                                initialCommitted.currentPlan(),
                                initialCommitted.executionStart());
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 47);
        var reserved =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                exactLease.ownerId(),
                                exactLease.fencingToken());
        var confirmed = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        ComposeHarness harness = harness(
                script(
                        PersistenceResult.found(initialCommitted),
                        PersistenceResult.found(postCommitted)),
                script(
                        contextNone(),
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(confirmed)),
                script(PersistenceResult.applied(exactLease)),
                script(),
                script(verifiedWorkspace(
                        exactSpec,
                        FINGERPRINT)),
                script(),
                script(PersistenceResult.applied(confirmed)));

        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                harness.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));

        assertNotSame(initialCommitted, postCommitted);
        assertSame(confirmed, ready.persistedContext());
        assertSame(exactSpec, ready.persistedContext()
                .materializationSpec());
    }

    @Test
    void composeAcquireResponseLossCanReconcileOnlyExactConfirmation() {
        var exactSpec = spec("delegate-acquire");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        var leaseAttempt = attempt();
        AtomicInteger delegated = new AtomicInteger();
        RuntimeException responseLoss =
                new IllegalStateException("delegate-" + SECRET);
        ComposeHarness reconciled = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        PersistenceResult.found(confirmed.reservation()),
                        PersistenceResult.found(confirmed)),
                script(actionThenThrow(
                        delegated::incrementAndGet,
                        responseLoss)),
                script(verifiedWorkspace(exactSpec, FINGERPRINT)));
        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                reconciled.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(leaseAttempt))));
        assertEquals(1, delegated.get());
        assertEquals(
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                ready.leaseDisposition());

        ComposeHarness unreconciled = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        PersistenceResult.found(confirmed.reservation()),
                        PersistenceResult.found(confirmed.reservation())),
                script(responseLoss),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                PlanExecutionContextCompositionProtocolCode
                        .COLLABORATOR_EXCEPTION,
                "planExecutionContextComposition.leaseAcquireResult",
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                responseLoss,
                () -> unreconciled.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(leaseAttempt))));
    }

    @Test
    void composeNonConfirmedAcquireOutcomesAreNeverPackagedAsSuccess() {
        var exactSpec = spec("nonconfirmed-acquire");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 31);
        ComposeHarness rejectedHarness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(contextNone(), contextNone()),
                script(PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD,
                        "planId")),
                script());
        var rejected = assertInstanceOf(
                PlanExecutionContextPersistenceRejected.class,
                rejectedHarness.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));
        assertEquals(
                PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                rejected.stage());
        assertEquals(
                PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                rejected.leaseDisposition());

        ComposeHarness nullHarness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(contextNone(), contextNone()),
                script(NULL),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                PlanExecutionContextCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                "planExecutionContextComposition.leaseAcquireResult",
                PlanExecutionContextLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                null,
                () -> nullHarness.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));

        var reserved = expectedReservation(exactSpec, exactLease);
        var confirmed = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        ComposeHarness replayedHarness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        contextNone(),
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(confirmed)),
                script(PersistenceResult.replayed(exactLease)),
                script(),
                script(verifiedWorkspace(
                        exactSpec,
                        FINGERPRINT)),
                script(),
                script(PersistenceResult.replayed(confirmed)));
        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                replayedHarness.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));
        assertEquals(
                PlanExecutionContextCompositionResolution.CONFIRM_REPLAYED,
                ready.resolution());
    }

    @Test
    void composeReservedAuthorityCannotDisappearOrChangeAfterAcquire() {
        var exactSpec = spec("reserved-authority");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        var initialReserved = confirmed.reservation();
        var changedReserved = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        PLAN_ID,
                        exactSpec,
                        "changed-owner",
                        initialReserved.fencingToken() + 1);
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 37);
        for (Object postContext : List.of(
                contextNone(),
                PersistenceResult.found(changedReserved))) {
            ComposeHarness harness = harness(
                    script(
                            PersistenceResult.found(sourceBacked),
                            PersistenceResult.found(sourceBacked)),
                    script(
                            PersistenceResult.found(initialReserved),
                            postContext),
                    script(PersistenceResult.applied(exactLease)),
                    script());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_LEASE_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postLeaseContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> harness.composer().compose(
                            new PlanExecutionContextCompositionRequest(
                                    PLAN_ID,
                                    Optional.empty(),
                                    Optional.of(leaseAttempt))));
        }
    }

    @Test
    void composePostLeaseRequiresExactSameCommittedAndConfirmedWorkspace() {
        var exactSpec = spec("post-authority");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var changedCommitted = committed(
                SECRET,
                Optional.of(
                        spec("different-stored-source")
                                .sourceProjectVersion()));
        var confirmed = persistedContext(exactSpec, FINGERPRINT);
        var leaseAttempt = attempt();
        ComposeHarness changedExecution = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(changedCommitted)),
                script(
                        PersistenceResult.found(confirmed.reservation()),
                        PersistenceResult.found(confirmed.reservation())),
                script(PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD,
                        "planId")),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage
                        .POST_LEASE_EXECUTION_START_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_EXECUTION_START_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postLeaseExecutionStartInspectResult.value",
                PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                null,
                () -> changedExecution.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(leaseAttempt))));

        ComposeHarness missingWorkspace = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        PersistenceResult.found(confirmed.reservation()),
                        PersistenceResult.found(confirmed)),
                script(PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD,
                        "planId")),
                script(new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "inspectMaterialization")));
        var workspaceRejected = assertInstanceOf(
                PlanExecutionContextWorkspaceRejected.class,
                missingWorkspace.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.empty(),
                                Optional.of(leaseAttempt))));
        assertEquals(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                workspaceRejected.workspaceErrorCode());
        assertEquals(
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                workspaceRejected.stage());
        assertEquals(
                PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                workspaceRejected.leaseDisposition());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "lease.acquire",
                        "execution.inspect",
                        "context.inspect",
                        "workspace.inspect"),
                missingWorkspace.trace());
        assertEquals(0, missingWorkspace.workspace().materializeCalls.get());
        assertEquals(0, missingWorkspace.contexts().reserveCalls.get());
        assertEquals(0, missingWorkspace.contexts().confirmCalls.get());
    }

    @Test
    void composeReserveRequestUsesPostLeaseH0AndCurrentLeaseAuthority() {
        var exactSpec = spec("reserve-request-authority");
        var initialCommitted = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var postCommitted =
                new io.paperagent.v2.persistence
                        .PersistedExecutionStartCommitted(
                                initialCommitted.bootstrap(),
                                initialCommitted.currentPlan(),
                                initialCommitted.executionStart());
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 53);
        var expected = expectedReservation(exactSpec, exactLease);
        var confirmed = expectedConfirmation(
                expected,
                exactLease,
                FINGERPRINT);
        ComposeHarness harness = harness(
                script(
                        PersistenceResult.found(initialCommitted),
                        PersistenceResult.found(postCommitted)),
                script(
                        contextNone(),
                        contextNone(),
                        PersistenceResult.found(expected),
                        PersistenceResult.found(confirmed)),
                script(PersistenceResult.applied(exactLease)),
                script(PersistenceResult.applied(expected)),
                script(verifiedWorkspace(
                        exactSpec,
                        FINGERPRINT)),
                script(),
                script(PersistenceResult.applied(confirmed)));

        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                harness.composer().compose(
                        new PlanExecutionContextCompositionRequest(
                                PLAN_ID,
                                Optional.of(exactSpec),
                                Optional.of(leaseAttempt))));

        assertSame(confirmed, ready.persistedContext());
        assertEquals(1, harness.contexts().reservationRequests.size());
        var request = harness.contexts().reservationRequests.get(0);
        var revision = postCommitted.currentPlan().latestRevision();
        var startedCheckpoint =
                postCommitted.executionStart().startedCheckpoint();
        assertEquals(PLAN_ID, request.planId());
        assertEquals(exactLease.leaseToken(), request.leaseToken());
        assertEquals(exactLease.fencingToken(), request.fencingToken());
        assertEquals(revision.id(), request.expectedRevisionId());
        assertEquals(revision.number(), request.expectedRevisionNumber());
        assertEquals(
                startedCheckpoint.version(),
                request.expectedCheckpointVersion());
        assertEquals(
                startedCheckpoint.checkpoint().lastEventSequence(),
                request.expectedEventHeadSequence());
        assertSame(exactSpec, request.materializationSpec());
        assertEquals(exactLease.ownerId(), expected.leaseOwnerId());
        assertEquals(exactLease.fencingToken(), expected.fencingToken());
        assertFalse(
                exactLease.fencingToken()
                        == postCommitted.executionStart().fencingToken());
        assertPostReserveTraceAndZeroForbidden(harness);
    }

    @Test
    void composePostReserveAuthorityReconcilesEveryLegalReserveObservation() {
        var exactSpec = spec("reserve-reconciliation");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 59);
        var expected = expectedReservation(exactSpec, exactLease);
        var historical =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                "historical-owner-" + SECRET,
                                5);
        RuntimeException responseLoss =
                new IllegalStateException("reserve-response-" + SECRET);
        List<ReserveObservationCase> observations = List.of(
                new ReserveObservationCase(
                        PersistenceResult.applied(expected),
                        true),
                new ReserveObservationCase(
                        PersistenceResult.replayed(expected),
                        true),
                new ReserveObservationCase(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId"),
                        false),
                new ReserveObservationCase(
                        NULL,
                        false),
                new ReserveObservationCase(
                        responseLoss,
                        false));
        for (ReserveObservationCase observation : observations) {
            for (boolean confirmedState : List.of(false, true)) {
                var observedReservation = observation.exactSuccess()
                        ? expected
                        : historical;
                Object observed = confirmedState
                        ? PersistenceResult.found(
                                confirmationFor(observedReservation))
                        : PersistenceResult.found(observedReservation);
                var confirmedByCurrentLease = expectedConfirmation(
                        observedReservation,
                        exactLease,
                        FINGERPRINT);
                ComposeHarness harness = harness(
                        script(
                                PersistenceResult.found(sourceBacked),
                                PersistenceResult.found(sourceBacked)),
                        script(
                                contextNone(),
                                contextNone(),
                                observed,
                                PersistenceResult.found(
                                        confirmedByCurrentLease)),
                        script(PersistenceResult.applied(exactLease)),
                        script(observation.scriptedResult()),
                        script(verifiedWorkspace(
                                exactSpec,
                                FINGERPRINT)),
                        script(),
                        script(PersistenceResult.applied(
                                confirmedByCurrentLease)));
                var request = new PlanExecutionContextCompositionRequest(
                        PLAN_ID,
                        Optional.of(exactSpec),
                        Optional.of(leaseAttempt));
                if (confirmedState) {
                    var ready = assertInstanceOf(
                            PlanExecutionContextReady.class,
                            harness.composer().compose(request));
                    assertEquals(
                            PlanExecutionContextCompositionResolution
                                    .OBSERVED_CONCURRENT_CONFIRMATION,
                            ready.resolution());
                    assertSame(
                            observedReservation,
                            ready.persistedContext().reservation());
                } else {
                    var ready = assertInstanceOf(
                            PlanExecutionContextReady.class,
                            harness.composer().compose(request));
                    assertEquals(
                            PlanExecutionContextCompositionResolution
                                    .CONFIRM_APPLIED,
                            ready.resolution());
                    assertSame(
                            confirmedByCurrentLease,
                            ready.persistedContext());
                }
                assertEquals(1, harness.contexts().reserveCalls.get());
                assertPostReserveTraceAndZeroForbidden(harness);
            }
        }
    }

    @Test
    void composeAbsentPostReserveContextMapsEveryLegalReserveObservation() {
        var exactSpec = spec("reserve-absent-reconciliation");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 61);
        var expected = expectedReservation(exactSpec, exactLease);

        ComposeHarness rejectedHarness = reserveHarness(
                sourceBacked,
                exactSpec,
                leaseAttempt,
                exactLease,
                contextNone(),
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"));
        var rejected = assertInstanceOf(
                PlanExecutionContextPersistenceRejected.class,
                rejectedHarness.composer().compose(compositionRequest(
                        exactSpec,
                        leaseAttempt)));
        assertEquals(
                PlanExecutionContextCompositionStage.RESERVE,
                rejected.stage());
        assertEquals(
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                rejected.leaseDisposition());
        assertPostReserveTraceAndZeroForbidden(rejectedHarness);

        RuntimeException responseLoss =
                new IllegalStateException("reserve-absent-" + SECRET);
        for (Object reserveResult : List.of(NULL, responseLoss)) {
            ComposeHarness harness = reserveHarness(
                    sourceBacked,
                    exactSpec,
                    leaseAttempt,
                    exactLease,
                    contextNone(),
                    reserveResult);
            var retry = assertInstanceOf(
                    PlanExecutionContextRetryRequired.class,
                    harness.composer().compose(compositionRequest(
                            exactSpec,
                            leaseAttempt)));
            assertEquals(
                    PlanExecutionContextCompositionStage.RESERVE,
                    retry.stage());
            assertEquals(
                    PlanExecutionContextRetryReason
                            .RESERVATION_INDETERMINATE,
                    retry.retryReason());
            assertEquals(
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    retry.leaseDisposition());
            assertFalse(retry.toString().contains(SECRET));
            assertPostReserveTraceAndZeroForbidden(harness);
        }

        for (Object reserveResult : List.of(
                PersistenceResult.applied(expected),
                PersistenceResult.replayed(expected))) {
            ComposeHarness harness = reserveHarness(
                    sourceBacked,
                    exactSpec,
                    leaseAttempt,
                    exactLease,
                    contextNone(),
                    reserveResult);
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_RESERVE_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_RECONCILIATION_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postReserveContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> harness.composer().compose(compositionRequest(
                            exactSpec,
                            leaseAttempt)));
            assertPostReserveTraceAndZeroForbidden(harness);
        }
    }

    @Test
    void composePostReserveContextCorruptionBeatsMalformedReserve() {
        var exactSpec = spec("post-reserve-context-precedence");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 67);
        var expected = expectedReservation(exactSpec, exactLease);
        RuntimeException contextFailure =
                new IllegalStateException("post-reserve-" + SECRET);
        List<PostReserveContextFailureCase> failures = List.of(
                new PostReserveContextFailureCase(
                        NULL,
                        PlanExecutionContextCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult",
                        null),
                new PostReserveContextFailureCase(
                        contextFailure,
                        PlanExecutionContextCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult",
                        contextFailure),
                new PostReserveContextFailureCase(
                        PersistenceResult.found(sourceBacked),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult.value",
                        null),
                new PostReserveContextFailureCase(
                        PersistenceResult.found(
                                new io.paperagent.v2.persistence
                                        .PersistedPlanExecutionContextReserved(
                                                PLAN_ID,
                                                spec("wrong-post-reserve-spec"),
                                                exactLease.ownerId(),
                                                exactLease.fencingToken())),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult.value",
                        null),
                new PostReserveContextFailureCase(
                        PersistenceResult.found(
                                confirmationFor(
                                        new io.paperagent.v2.persistence
                                                .PersistedPlanExecutionContextReserved(
                                                        PLAN_ID,
                                                        spec("wrong-confirmed-spec"),
                                                        exactLease.ownerId(),
                                                        exactLease
                                                                .fencingToken()))),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult.value",
                        null));
        for (PostReserveContextFailureCase failure : failures) {
            ComposeHarness harness = reserveHarness(
                    sourceBacked,
                    exactSpec,
                    leaseAttempt,
                    exactLease,
                    failure.contextResult(),
                    PersistenceResult.found(expected));
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_RESERVE_CONTEXT_INSPECT,
                    failure.code(),
                    failure.path(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    failure.cause(),
                    () -> harness.composer().compose(compositionRequest(
                            exactSpec,
                            leaseAttempt)));
            assertPostReserveTraceAndZeroForbidden(harness);
        }
    }

    @Test
    void composePostReservePartialStateBeatsEveryLegalReserveObservation() {
        var exactSpec = spec("post-reserve-partial");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 69);
        var expected = expectedReservation(exactSpec, exactLease);
        RuntimeException responseLoss =
                new IllegalStateException("reserve-partial-" + SECRET);
        for (Object reserveResult : List.of(
                PersistenceResult.applied(expected),
                PersistenceResult.replayed(expected),
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"),
                NULL,
                responseLoss)) {
            ComposeHarness harness = reserveHarness(
                    sourceBacked,
                    exactSpec,
                    leaseAttempt,
                    exactLease,
                    PersistenceResult.rejected(
                            PersistenceErrorCode
                                    .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                            "planExecutionContext"),
                    reserveResult);
            var rejected = assertInstanceOf(
                    PlanExecutionContextPersistenceRejected.class,
                    harness.composer().compose(compositionRequest(
                            exactSpec,
                            leaseAttempt)));
            assertEquals(
                    PlanExecutionContextCompositionStage
                            .POST_RESERVE_CONTEXT_INSPECT,
                    rejected.stage());
            assertEquals(
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    rejected.leaseDisposition());
            assertPostReserveTraceAndZeroForbidden(harness);
        }
    }

    @Test
    void composeMalformedReserveBeatsTypedOrAuthoritativePostContext() {
        var exactSpec = spec("malformed-reserve-precedence");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 71);
        var expected = expectedReservation(exactSpec, exactLease);
        var wrongReserved =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                "wrong-result-owner",
                                expected.fencingToken() + 1);
        List<MalformedReserveCase> malformed = List.of(
                new MalformedReserveCase(
                        PersistenceResult.found(expected),
                        PlanExecutionContextCompositionProtocolCode
                                .UNEXPECTED_PERSISTENCE_OUTCOME,
                        "planExecutionContextComposition"
                                + ".reserveResult.outcome"),
                new MalformedReserveCase(
                        PersistenceResult.applied(wrongReserved),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".reserveResult.value"),
                new MalformedReserveCase(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId." + SECRET),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".reserveResult.failure"));
        List<Object> postContexts = List.of(
                PersistenceResult.rejected(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                        "planExecutionContext"),
                PersistenceResult.found(expected),
                PersistenceResult.found(confirmationFor(expected)));
        for (MalformedReserveCase reserve : malformed) {
            for (Object postContext : postContexts) {
                ComposeHarness harness = reserveHarness(
                        sourceBacked,
                        exactSpec,
                        leaseAttempt,
                        exactLease,
                        postContext,
                        reserve.reserveResult());
                assertProtocolSanitized(
                        PlanExecutionContextCompositionStage.RESERVE,
                        reserve.code(),
                        reserve.path(),
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                        null,
                        () -> harness.composer().compose(compositionRequest(
                                exactSpec,
                                leaseAttempt)));
                assertPostReserveTraceAndZeroForbidden(harness);
            }
        }
    }

    @Test
    void composeSuccessfulReserveRequiresExactObservedReservation() {
        var exactSpec = spec("successful-reserve-reconciliation");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 73);
        var expected = expectedReservation(exactSpec, exactLease);
        var historical =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                "historical-owner-" + SECRET,
                                3);
        for (Object reserveResult : List.of(
                PersistenceResult.applied(expected),
                PersistenceResult.replayed(expected))) {
            for (Object postContext : List.of(
                    PersistenceResult.found(historical),
                    PersistenceResult.found(
                            confirmationFor(historical)))) {
                ComposeHarness harness = reserveHarness(
                        sourceBacked,
                        exactSpec,
                        leaseAttempt,
                        exactLease,
                        postContext,
                        reserveResult);
                assertProtocolSanitized(
                        PlanExecutionContextCompositionStage
                                .POST_RESERVE_CONTEXT_INSPECT,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_RECONCILIATION_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult.value",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                        null,
                        () -> harness.composer().compose(compositionRequest(
                                exactSpec,
                                leaseAttempt)));
                assertPostReserveTraceAndZeroForbidden(harness);
            }
        }
    }

    @Test
    void composeReserveDelegateThenThrowStillUsesCapturedAuthority() {
        var exactSpec = spec("reserve-delegate-then-throw");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 79);
        var historical =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                "historical-owner-" + SECRET,
                                7);
        AtomicInteger delegated = new AtomicInteger();
        RuntimeException responseLoss =
                new IllegalStateException("reserve-delegate-" + SECRET);
        var confirmed = expectedConfirmation(
                historical,
                exactLease,
                FINGERPRINT);
        ComposeHarness harness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        contextNone(),
                        contextNone(),
                        PersistenceResult.found(historical),
                        PersistenceResult.found(confirmed)),
                script(PersistenceResult.applied(exactLease)),
                script(actionThenThrow(
                        delegated::incrementAndGet,
                        responseLoss)),
                script(verifiedWorkspace(
                        exactSpec,
                        FINGERPRINT)),
                script(),
                script(PersistenceResult.applied(confirmed)));

        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                harness.composer().compose(compositionRequest(
                        exactSpec,
                        leaseAttempt)));
        assertEquals(1, delegated.get());
        assertSame(confirmed, ready.persistedContext());
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "lease.acquire",
                        "execution.inspect",
                        "context.inspect",
                        "context.reserve",
                        "context.inspect",
                        "workspace.inspect",
                        "context.confirm",
                        "context.inspect"),
                harness.trace());
        assertEquals(1, harness.contexts().confirmCalls.get());
    }

    @Test
    void composeMaterializeDelegateThenThrowAlwaysUsesPostInspection() {
        var exactSpec = spec("materialize-delegate-then-throw");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 81);
        var reserved = expectedReservation(exactSpec, exactLease);
        RuntimeException responseLoss =
                new IllegalStateException(
                        "materialize-delegate-" + SECRET);

        AtomicInteger verifiedSideEffect = new AtomicInteger();
        var postAuthority = verifiedWorkspace(exactSpec, FINGERPRINT);
        var confirmed = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        ComposeHarness verifiedHarness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(confirmed)),
                script(PersistenceResult.applied(exactLease)),
                script(),
                script(missingWorkspace(), postAuthority),
                script(actionThenThrow(
                        verifiedSideEffect::incrementAndGet,
                        responseLoss)),
                script(PersistenceResult.applied(confirmed)));
        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                verifiedHarness.composer().compose(
                        compositionRequest(
                                exactSpec,
                                leaseAttempt)));
        assertEquals(1, verifiedSideEffect.get());
        assertSame(confirmed, ready.persistedContext());
        assertSame(postAuthority, ready.verifiedWorkspace());
        assertFalse(ready.toString().contains(SECRET));
        assertComposeMaterializeAndConfirmTrace(
                verifiedHarness,
                exactSpec);

        AtomicInteger missingSideEffect = new AtomicInteger();
        ComposeHarness missingHarness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(reserved)),
                script(PersistenceResult.applied(exactLease)),
                script(),
                script(missingWorkspace(), missingWorkspace()),
                script(actionThenThrow(
                        missingSideEffect::incrementAndGet,
                        responseLoss)));
        var retry = assertInstanceOf(
                PlanExecutionContextRetryRequired.class,
                missingHarness.composer().compose(
                        compositionRequest(
                                exactSpec,
                                leaseAttempt)));
        assertEquals(1, missingSideEffect.get());
        assertEquals(
                PlanExecutionContextCompositionStage
                        .WORKSPACE_MATERIALIZE,
                retry.stage());
        assertEquals(
                PlanExecutionContextRetryReason
                        .MATERIALIZATION_INDETERMINATE,
                retry.retryReason());
        assertEquals(
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                retry.leaseDisposition());
        assertFalse(retry.toString().contains(SECRET));
        assertComposeMaterializeTraceAndZeroConfirm(
                missingHarness,
                exactSpec);
    }

    @Test
    void composeRejectsSecretProjectPathNotFoundBeforeMaterialize() {
        var exactSpec = spec("secret-project-path-not-found");
        var sourceBacked = committed(
                SECRET,
                Optional.of(exactSpec.sourceProjectVersion()));
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 82);
        var reserved = expectedReservation(exactSpec, exactLease);
        var secretProjectPath =
                new ProjectPath("private/" + SECRET + ".txt");
        ComposeHarness harness = harness(
                script(
                        PersistenceResult.found(sourceBacked),
                        PersistenceResult.found(sourceBacked)),
                script(
                        PersistenceResult.found(reserved),
                        PersistenceResult.found(reserved)),
                script(PersistenceResult.applied(exactLease)),
                script(),
                script(new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "inspectMaterialization",
                        secretProjectPath)),
                script());

        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceInspectResult.failure",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> harness.composer().compose(
                        compositionRequest(
                                exactSpec,
                                leaseAttempt)));
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "lease.acquire",
                        "execution.inspect",
                        "context.inspect",
                        "workspace.inspect"),
                harness.trace());
        assertEquals(1, harness.workspace().inspectCalls.get());
        assertEquals(0, harness.workspace().materializeCalls.get());
        assertEquals(0, harness.contexts().confirmCalls.get());
    }

    @Test
    void reservedWorkspaceInspectionOnlyMaterializesCanonicalNotFound() {
        var exactSpec = spec("reserved-workspace-gate");
        var exactLease = lease(attempt(), 83);
        var reserved = expectedReservation(exactSpec, exactLease);
        var inspected = verifiedWorkspace(exactSpec, FINGERPRINT);
        ComposeHarness exact = confirmedWorkspaceHarness(
                reserved,
                exactLease,
                FINGERPRINT,
                script(inspected),
                script());

        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                continueReserved(
                        exact,
                        reserved,
                        exactLease));
        assertSame(inspected, ready.verifiedWorkspace());
        assertEquals(
                List.of(
                        "workspace.inspect",
                        "context.confirm",
                        "context.inspect"),
                exact.trace());
        assertEquals(0, exact.workspace().materializeCalls.get());

        for (WorkspaceErrorCode code : POST_MATERIALIZE_CODES) {
            ComposeHarness rejectedHarness = workspaceHarness(
                    script(new WorkspaceException(
                            code,
                            "inspectMaterialization")),
                    script());
            var rejected = assertInstanceOf(
                    PlanExecutionContextWorkspaceRejected.class,
                    continueReserved(
                            rejectedHarness,
                            reserved,
                            exactLease));
            assertEquals(
                    PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                    rejected.stage());
            assertEquals(code, rejected.workspaceErrorCode());
            assertEquals(
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    rejected.leaseDisposition());
            assertEquals(
                    List.of("workspace.inspect"),
                    rejectedHarness.trace());
            assertEquals(
                    0,
                    rejectedHarness.workspace().materializeCalls.get());
        }

        ComposeHarness malformedNotFound = workspaceHarness(
                script(new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "materialize")),
                script());
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceInspectResult.failure",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        malformedNotFound,
                        reserved,
                        exactLease));
        assertEquals(0, malformedNotFound.workspace().materializeCalls.get());
    }

    @Test
    void postMaterializeMalformedInspectionHasFirstPriority() {
        var exactSpec = spec("post-materialize-p1");
        var exactLease = lease(attempt(), 89);
        var reserved = expectedReservation(exactSpec, exactLease);
        RuntimeException postFailure =
                new IllegalStateException("post-inspect-" + SECRET);
        List<PostMaterializeProtocolCase> cases = List.of(
                new PostMaterializeProtocolCase(
                        NULL,
                        PlanExecutionContextCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "planExecutionContextComposition"
                                + ".postMaterializeWorkspaceInspectResult",
                        null),
                new PostMaterializeProtocolCase(
                        postFailure,
                        PlanExecutionContextCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "planExecutionContextComposition"
                                + ".postMaterializeWorkspaceInspectResult",
                        postFailure),
                new PostMaterializeProtocolCase(
                        new WorkspaceException(
                                WorkspaceErrorCode.IO_FAILURE,
                                "inspectMaterialization"),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_WORKSPACE_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postMaterializeWorkspaceInspectResult"
                                + ".failure",
                        null),
                new PostMaterializeProtocolCase(
                        verifiedWorkspace(
                                spec("wrong-post-inspect-spec"),
                                FINGERPRINT),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_WORKSPACE_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".postMaterializeWorkspaceInspectResult"
                                + ".value",
                        null));
        for (PostMaterializeProtocolCase testCase : cases) {
            ComposeHarness harness = workspaceHarness(
                    script(
                            missingWorkspace(),
                            testCase.postInspection()),
                    script(verifiedWorkspace(
                            spec("malformed-materialize-hidden"),
                            WRONG_FINGERPRINT)));
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_MATERIALIZE_WORKSPACE_INSPECT,
                    testCase.code(),
                    testCase.path(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    testCase.cause(),
                    () -> continueReserved(
                            harness,
                            reserved,
                            exactLease));
            assertWorkspaceMaterializeTrace(harness);
        }
    }

    @Test
    void postMaterializeStableFailureOverridesMaterializeObservation() {
        var exactSpec = spec("post-materialize-p2");
        var exactLease = lease(attempt(), 97);
        var reserved = expectedReservation(exactSpec, exactLease);
        for (WorkspaceErrorCode code : POST_MATERIALIZE_CODES) {
            ComposeHarness harness = workspaceHarness(
                    script(
                            missingWorkspace(),
                            new WorkspaceException(
                                    code,
                                    "inspectMaterialization")),
                    script(verifiedWorkspace(
                            spec("wrong-materialize-overridden"),
                            WRONG_FINGERPRINT)));
            var rejected = assertInstanceOf(
                    PlanExecutionContextWorkspaceRejected.class,
                    continueReserved(
                            harness,
                            reserved,
                            exactLease));
            assertEquals(
                    PlanExecutionContextCompositionStage
                            .POST_MATERIALIZE_WORKSPACE_INSPECT,
                    rejected.stage());
            assertEquals(code, rejected.workspaceErrorCode());
            assertWorkspaceMaterializeTrace(harness);
        }
    }

    @Test
    void postMaterializeNotFoundUsesFrozenMaterializeMatrix() {
        var exactSpec = spec("post-materialize-p3");
        var exactLease = lease(attempt(), 101);
        var reserved = expectedReservation(exactSpec, exactLease);
        var exactMaterialization =
                verifiedWorkspace(exactSpec, FINGERPRINT);
        ComposeHarness contradictorySuccess = workspaceHarness(
                script(missingWorkspace(), missingWorkspace()),
                script(exactMaterialization));
        assertPostMaterializeReconciliationProtocol(
                contradictorySuccess,
                reserved,
                exactLease);

        ComposeHarness malformedSuccess = workspaceHarness(
                script(missingWorkspace(), missingWorkspace()),
                script(verifiedWorkspace(
                        spec("wrong-materialize-spec"),
                        FINGERPRINT)));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceMaterializeResult.value",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        malformedSuccess,
                        reserved,
                        exactLease));
        assertWorkspaceMaterializeTrace(malformedSuccess);

        ComposeHarness nonCanonical = workspaceHarness(
                script(missingWorkspace(), missingWorkspace()),
                script(new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "materialize")));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceMaterializeResult.failure",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        nonCanonical,
                        reserved,
                        exactLease));
        assertWorkspaceMaterializeTrace(nonCanonical);

        ComposeHarness stableFailure = workspaceHarness(
                script(missingWorkspace(), missingWorkspace()),
                script(new WorkspaceException(
                        WorkspaceErrorCode.SOURCE_FAILURE,
                        "materialize")));
        var rejected = assertInstanceOf(
                PlanExecutionContextWorkspaceRejected.class,
                continueReserved(
                        stableFailure,
                        reserved,
                        exactLease));
        assertEquals(
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE,
                rejected.stage());
        assertEquals(
                WorkspaceErrorCode.SOURCE_FAILURE,
                rejected.workspaceErrorCode());
        assertWorkspaceMaterializeTrace(stableFailure);

        RuntimeException responseLoss =
                new IllegalStateException("materialize-" + SECRET);
        for (Object response : List.of(NULL, responseLoss)) {
            ComposeHarness indeterminate = workspaceHarness(
                    script(missingWorkspace(), missingWorkspace()),
                    script(response));
            var retry = assertInstanceOf(
                    PlanExecutionContextRetryRequired.class,
                    continueReserved(
                            indeterminate,
                            reserved,
                            exactLease));
            assertEquals(
                    PlanExecutionContextCompositionStage
                            .WORKSPACE_MATERIALIZE,
                    retry.stage());
            assertEquals(
                    PlanExecutionContextRetryReason
                            .MATERIALIZATION_INDETERMINATE,
                    retry.retryReason());
            assertWorkspaceMaterializeTrace(indeterminate);
        }
    }

    @Test
    void verifiedPostMaterializationReconcilesAndCarriesLaterAuthority() {
        var exactSpec = spec("post-materialize-p4");
        var exactLease = lease(attempt(), 103);
        var reserved = expectedReservation(exactSpec, exactLease);
        var materialized = verifiedWorkspace(exactSpec, FINGERPRINT);
        var postInspected = verifiedWorkspace(exactSpec, FINGERPRINT);
        assertNotSame(materialized, postInspected);
        ComposeHarness equalDistinct = confirmedWorkspaceHarness(
                reserved,
                exactLease,
                FINGERPRINT,
                script(missingWorkspace(), postInspected),
                script(materialized));
        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                continueReserved(
                        equalDistinct,
                        reserved,
                        exactLease));
        assertSame(postInspected, ready.verifiedWorkspace());
        assertWorkspaceMaterializeAndConfirmTrace(equalDistinct);

        for (Object response : List.of(
                NULL,
                new IllegalStateException(
                        "materialize-response-" + SECRET),
                new WorkspaceException(
                        WorkspaceErrorCode.SOURCE_FAILURE,
                        "materialize"))) {
            var authoritative = verifiedWorkspace(exactSpec, FINGERPRINT);
            ComposeHarness reconciled = confirmedWorkspaceHarness(
                    reserved,
                    exactLease,
                    FINGERPRINT,
                    script(missingWorkspace(), authoritative),
                    script(response));
            var responseLossReady = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    continueReserved(
                            reconciled,
                            reserved,
                            exactLease));
            assertSame(
                    authoritative,
                    responseLossReady.verifiedWorkspace());
            assertWorkspaceMaterializeAndConfirmTrace(reconciled);
        }

        ComposeHarness conflicting = workspaceHarness(
                script(
                        missingWorkspace(),
                        verifiedWorkspace(
                                exactSpec,
                                WRONG_FINGERPRINT)),
                script(materialized));
        assertPostMaterializeReconciliationProtocol(
                conflicting,
                reserved,
                exactLease);

        ComposeHarness malformedMaterialize = workspaceHarness(
                script(missingWorkspace(), postInspected),
                script(verifiedWorkspace(
                        spec("wrong-p4-materialize-spec"),
                        FINGERPRINT)));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceMaterializeResult.value",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        malformedMaterialize,
                        reserved,
                        exactLease));
        assertWorkspaceMaterializeTrace(malformedMaterialize);

        ComposeHarness nonCanonicalMaterialize = workspaceHarness(
                script(missingWorkspace(), postInspected),
                script(new WorkspaceException(
                        WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                        "materialize")));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_WORKSPACE_AUTHORITY,
                "planExecutionContextComposition"
                        + ".workspaceMaterializeResult.failure",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        nonCanonicalMaterialize,
                        reserved,
                        exactLease));
        assertWorkspaceMaterializeTrace(nonCanonicalMaterialize);
    }

    @Test
    void confirmRequestUsesOnlyReservedLeaseAndVerifiedAuthority() {
        var exactSpec = spec("confirm-request-provenance");
        var leaseAttempt = attempt();
        var exactLease = lease(leaseAttempt, 107);
        var reserved =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                exactSpec,
                                "historical-owner-" + SECRET,
                                7);
        var verified = verifiedWorkspace(
                exactSpec,
                WRONG_FINGERPRINT);
        var returnedConfirmation = expectedConfirmation(
                reserved,
                exactLease,
                WRONG_FINGERPRINT);
        var postConfirmation = expectedConfirmation(
                reserved,
                exactLease,
                WRONG_FINGERPRINT);
        assertNotSame(returnedConfirmation, postConfirmation);
        ComposeHarness harness = confirmHarness(
                reserved,
                exactLease,
                verified,
                PersistenceResult.found(postConfirmation),
                PersistenceResult.applied(returnedConfirmation));

        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                continueReserved(harness, reserved, exactLease));

        assertSame(postConfirmation, ready.persistedContext());
        assertSame(verified, ready.verifiedWorkspace());
        assertEquals(1, harness.contexts().confirmationRequests.size());
        var request = harness.contexts().confirmationRequests.get(0);
        assertSame(reserved.planId(), request.planId());
        assertSame(exactLease.leaseToken(), request.leaseToken());
        assertEquals(
                exactLease.fencingToken(),
                request.fencingToken());
        assertSame(
                reserved.materializationSpec(),
                request.materializationSpec());
        assertSame(
                verified.sourceManifestFingerprint(),
                request.sourceManifestFingerprint());
        assertDirectConfirmTrace(harness);
    }

    @Test
    void confirmResolutionUsesObservationNotConfirmerIdentity() {
        var exactSpec = spec("confirm-resolution");
        var exactLease = lease(attempt(), 109);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        var concurrent =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextConfirmed(
                                reserved,
                                "concurrent-owner-" + SECRET,
                                exactLease.fencingToken() + 1,
                                FINGERPRINT);
        RuntimeException responseLoss =
                new IllegalStateException("confirm-" + SECRET);
        List<ConfirmResolutionCase> cases = List.of(
                new ConfirmResolutionCase(
                        PersistenceResult.applied(expected),
                        expected,
                        PlanExecutionContextCompositionResolution
                                .CONFIRM_APPLIED),
                new ConfirmResolutionCase(
                        PersistenceResult.replayed(expected),
                        expected,
                        PlanExecutionContextCompositionResolution
                                .CONFIRM_REPLAYED),
                new ConfirmResolutionCase(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId"),
                        concurrent,
                        PlanExecutionContextCompositionResolution
                                .OBSERVED_CONCURRENT_CONFIRMATION),
                new ConfirmResolutionCase(
                        NULL,
                        concurrent,
                        PlanExecutionContextCompositionResolution
                                .RECONCILED_AFTER_RESPONSE_LOSS),
                new ConfirmResolutionCase(
                        responseLoss,
                        concurrent,
                        PlanExecutionContextCompositionResolution
                                .RECONCILED_AFTER_RESPONSE_LOSS));
        for (ConfirmResolutionCase testCase : cases) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    PersistenceResult.found(
                            testCase.postConfirmation()),
                    testCase.confirmResult());
            var ready = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    continueReserved(harness, reserved, exactLease));
            assertEquals(testCase.resolution(), ready.resolution());
            assertSame(
                    testCase.postConfirmation(),
                    ready.persistedContext());
            assertSame(verified, ready.verifiedWorkspace());
            assertFalse(ready.toString().contains(SECRET));
            assertDirectConfirmTrace(harness);
        }
    }

    @Test
    void postConfirmSemanticCorruptionBeatsMalformedConfirm() {
        var exactSpec = spec("post-confirm-p1");
        var exactLease = lease(attempt(), 113);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var changedReservation =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextReserved(
                                PLAN_ID,
                                spec("changed-post-confirm-spec"),
                                exactLease.ownerId(),
                                exactLease.fencingToken());
        RuntimeException postFailure =
                new IllegalStateException("post-confirm-" + SECRET);
        String postPath = "planExecutionContextComposition"
                + ".postConfirmContextInspectResult";
        List<PostConfirmSemanticCase> cases = List.of(
                new PostConfirmSemanticCase(
                        NULL,
                        PlanExecutionContextCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        postPath,
                        null),
                new PostConfirmSemanticCase(
                        postFailure,
                        PlanExecutionContextCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        postPath,
                        postFailure),
                new PostConfirmSemanticCase(
                        contextNone(),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        postPath + ".value",
                        null),
                new PostConfirmSemanticCase(
                        PersistenceResult.found(changedReservation),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        postPath + ".value",
                        null),
                new PostConfirmSemanticCase(
                        PersistenceResult.found(
                                confirmationFor(changedReservation)),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_CONTEXT_AUTHORITY,
                        postPath + ".value",
                        null));
        for (PostConfirmSemanticCase testCase : cases) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    testCase.postContext(),
                    PersistenceResult.found(
                            expectedConfirmation(
                                    reserved,
                                    exactLease,
                                    FINGERPRINT)));
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_CONFIRM_CONTEXT_INSPECT,
                    testCase.code(),
                    testCase.path(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    testCase.cause(),
                    () -> continueReserved(
                            harness,
                            reserved,
                            exactLease));
            assertDirectConfirmTrace(harness);
        }
    }

    @Test
    void malformedConfirmBeatsWrongPostConfirmFingerprint() {
        var exactSpec = spec("confirm-before-fingerprint");
        var exactLease = lease(attempt(), 119);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        var wrongFingerprint = expectedConfirmation(
                reserved,
                exactLease,
                WRONG_FINGERPRINT);
        ComposeHarness harness = confirmHarness(
                reserved,
                exactLease,
                verified,
                PersistenceResult.found(wrongFingerprint),
                PersistenceResult.found(expected));

        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.CONFIRM,
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "planExecutionContextComposition.confirmResult.outcome",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        harness,
                        reserved,
                        exactLease));
        assertDirectConfirmTrace(harness);
    }

    @Test
    void validConfirmObservationsLoseToWrongPostConfirmFingerprint() {
        var exactSpec = spec("fingerprint-reconciliation");
        var exactLease = lease(attempt(), 123);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        var wrongFingerprint = expectedConfirmation(
                reserved,
                exactLease,
                WRONG_FINGERPRINT);
        for (Object confirmResult : List.of(
                PersistenceResult.applied(expected),
                PersistenceResult.replayed(expected),
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"),
                NULL,
                new IllegalStateException(
                        "fingerprint-response-loss-" + SECRET))) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    PersistenceResult.found(wrongFingerprint),
                    confirmResult);

            assertPostConfirmReconciliationProtocol(
                    harness,
                    reserved,
                    exactLease);
        }
    }

    @Test
    void malformedConfirmBeatsLegalPostConfirmState() {
        var exactSpec = spec("confirm-p2");
        var exactLease = lease(attempt(), 127);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        var wrongSuccess =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextConfirmed(
                                reserved,
                                "wrong-success-owner-" + SECRET,
                                exactLease.fencingToken() + 1,
                                FINGERPRINT);
        List<MalformedConfirmCase> cases = List.of(
                new MalformedConfirmCase(
                        PersistenceResult.found(expected),
                        PlanExecutionContextCompositionProtocolCode
                                .UNEXPECTED_PERSISTENCE_OUTCOME,
                        "planExecutionContextComposition"
                                + ".confirmResult.outcome"),
                new MalformedConfirmCase(
                        PersistenceResult.applied(wrongSuccess),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_RECONCILIATION_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".confirmResult.value"),
                new MalformedConfirmCase(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.STALE_VERSION,
                                "request." + SECRET),
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_RECONCILIATION_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".confirmResult.failure"));
        for (MalformedConfirmCase testCase : cases) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    PersistenceResult.found(expected),
                    testCase.confirmResult());
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage.CONFIRM,
                    testCase.code(),
                    testCase.path(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> continueReserved(
                            harness,
                            reserved,
                            exactLease));
            assertDirectConfirmTrace(harness);
        }
    }

    @Test
    void confirmSuccessRejectsEverySingleFieldMismatch() {
        var exactSpec = spec("confirm-success-field-flips");
        var exactLease = lease(attempt(), 129);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        var planFlip = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        new io.paperagent.v2.contracts.PlanId(
                                "confirm-plan-flip"),
                        exactSpec,
                        reserved.leaseOwnerId(),
                        reserved.fencingToken());
        var specFlip = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        reserved.planId(),
                        spec("confirm-spec-flip"),
                        reserved.leaseOwnerId(),
                        reserved.fencingToken());
        var reservationOwnerFlip = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        reserved.planId(),
                        exactSpec,
                        "historical-owner-flip-" + SECRET,
                        reserved.fencingToken());
        var reservationFenceFlip = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        reserved.planId(),
                        exactSpec,
                        reserved.leaseOwnerId(),
                        reserved.fencingToken() + 1);
        List<io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed> fieldFlips =
                List.of(
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        planFlip,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken(),
                                        FINGERPRINT),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        specFlip,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken(),
                                        FINGERPRINT),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reservationOwnerFlip,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken(),
                                        FINGERPRINT),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reservationFenceFlip,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken(),
                                        FINGERPRINT),
                        expectedConfirmation(
                                reserved,
                                exactLease,
                                WRONG_FINGERPRINT),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reserved,
                                        "confirmer-owner-flip-" + SECRET,
                                        exactLease.fencingToken(),
                                        FINGERPRINT),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reserved,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken() + 1,
                                        FINGERPRINT));
        for (PersistenceOutcomeFactory outcome :
                PersistenceOutcomeFactory.values()) {
            for (var fieldFlip : fieldFlips) {
                ComposeHarness harness = confirmHarness(
                        reserved,
                        exactLease,
                        verified,
                        PersistenceResult.found(expected),
                        outcome.result(fieldFlip));

                assertProtocolSanitized(
                        PlanExecutionContextCompositionStage.CONFIRM,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_RECONCILIATION_AUTHORITY,
                        "planExecutionContextComposition"
                                + ".confirmResult.value",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                        null,
                        () -> continueReserved(
                                harness,
                                reserved,
                                exactLease));
                assertDirectConfirmTrace(harness);
            }
        }
    }

    @Test
    void postConfirmContextRejectsEveryPermanentReservationFieldFlip() {
        var exactSpec = spec("post-confirm-reservation-flips");
        var exactLease = lease(attempt(), 130);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        List<io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved> fieldFlips =
                List.of(
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextReserved(
                                        new io.paperagent.v2.contracts.PlanId(
                                                "context-plan-flip"),
                                        exactSpec,
                                        reserved.leaseOwnerId(),
                                        reserved.fencingToken()),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextReserved(
                                        reserved.planId(),
                                        spec("context-spec-flip"),
                                        reserved.leaseOwnerId(),
                                        reserved.fencingToken()),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextReserved(
                                        reserved.planId(),
                                        exactSpec,
                                        "context-owner-flip-" + SECRET,
                                        reserved.fencingToken()),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextReserved(
                                        reserved.planId(),
                                        exactSpec,
                                        reserved.leaseOwnerId(),
                                        reserved.fencingToken() + 1));
        List<Object> postContexts = new ArrayList<>();
        postContexts.addAll(fieldFlips.stream()
                .map(PersistenceResult::found)
                .toList());
        postContexts.addAll(fieldFlips.stream()
                .map(flip -> PersistenceResult.found(
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        flip,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken(),
                                        FINGERPRINT)))
                .toList());
        for (Object postContext : postContexts) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    postContext,
                    PersistenceResult.applied(expected));

            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_CONFIRM_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postConfirmContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> continueReserved(
                            harness,
                            reserved,
                            exactLease));
            assertDirectConfirmTrace(harness);
        }
    }

    @Test
    void exactConfirmSuccessRejectsEachConfirmerMetadataMismatch() {
        var exactSpec = spec("post-confirm-confirmer-flips");
        var exactLease = lease(attempt(), 130);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        List<io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed> fieldFlips =
                List.of(
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reserved,
                                        "winner-owner-flip-" + SECRET,
                                        exactLease.fencingToken(),
                                        FINGERPRINT),
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reserved,
                                        exactLease.ownerId(),
                                        exactLease.fencingToken() + 1,
                                        FINGERPRINT));
        for (var fieldFlip : fieldFlips) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    PersistenceResult.found(fieldFlip),
                    PersistenceResult.applied(expected));

            assertPostConfirmReconciliationProtocol(
                    harness,
                    reserved,
                    exactLease);
        }
    }

    @Test
    void rejectionAndResponseLossAcceptEachConcurrentConfirmerField() {
        var exactSpec = spec("concurrent-confirmer-flips");
        var exactLease = lease(attempt(), 130);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var winnerOwnerFlip =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextConfirmed(
                                reserved,
                                "winner-owner-only-" + SECRET,
                                exactLease.fencingToken(),
                                FINGERPRINT);
        var winnerFenceFlip =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextConfirmed(
                                reserved,
                                exactLease.ownerId(),
                                exactLease.fencingToken() + 1,
                                FINGERPRINT);
        List<ConfirmResolutionCase> cases = new ArrayList<>();
        for (var winner : List.of(winnerOwnerFlip, winnerFenceFlip)) {
            cases.add(new ConfirmResolutionCase(
                    PersistenceResult.rejected(
                            PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.planId"),
                    winner,
                    PlanExecutionContextCompositionResolution
                            .OBSERVED_CONCURRENT_CONFIRMATION));
            cases.add(new ConfirmResolutionCase(
                    NULL,
                    winner,
                    PlanExecutionContextCompositionResolution
                            .RECONCILED_AFTER_RESPONSE_LOSS));
            cases.add(new ConfirmResolutionCase(
                    new IllegalStateException(
                            "concurrent-response-loss-" + SECRET),
                    winner,
                    PlanExecutionContextCompositionResolution
                            .RECONCILED_AFTER_RESPONSE_LOSS));
        }
        for (ConfirmResolutionCase testCase : cases) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    PersistenceResult.found(
                            testCase.postConfirmation()),
                    testCase.confirmResult());

            var ready = assertInstanceOf(
                    PlanExecutionContextReady.class,
                    continueReserved(harness, reserved, exactLease));
            assertEquals(testCase.resolution(), ready.resolution());
            assertSame(
                    testCase.postConfirmation(),
                    ready.persistedContext());
            assertSame(verified, ready.verifiedWorkspace());
            assertFalse(ready.toString().contains(SECRET));
            assertDirectConfirmTrace(harness);
        }
    }

    @Test
    void postConfirmPartialAndReservedUseFrozenPrecedence() {
        var exactSpec = spec("confirm-p3-p4");
        var exactLease = lease(attempt(), 131);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var expected = expectedConfirmation(
                reserved,
                exactLease,
                FINGERPRINT);
        Object partial = PersistenceResult.rejected(
                PersistenceErrorCode
                        .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
        for (Object confirmResult : List.of(
                PersistenceResult.applied(expected),
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"),
                NULL,
                new IllegalStateException("partial-" + SECRET))) {
            ComposeHarness harness = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    partial,
                    confirmResult);
            var rejected = assertInstanceOf(
                    PlanExecutionContextPersistenceRejected.class,
                    continueReserved(harness, reserved, exactLease));
            assertEquals(
                    PlanExecutionContextCompositionStage
                            .POST_CONFIRM_CONTEXT_INSPECT,
                    rejected.stage());
            assertDirectConfirmTrace(harness);
        }

        ComposeHarness malformedBeforePartial = confirmHarness(
                reserved,
                exactLease,
                verified,
                partial,
                PersistenceResult.found(expected));
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage.CONFIRM,
                PlanExecutionContextCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "planExecutionContextComposition.confirmResult.outcome",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        malformedBeforePartial,
                        reserved,
                        exactLease));
        assertDirectConfirmTrace(malformedBeforePartial);

        for (Object confirmResult : List.of(
                PersistenceResult.applied(expected),
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"),
                NULL,
                new IllegalStateException("none-" + SECRET))) {
            ComposeHarness regressed = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    contextNone(),
                    confirmResult);
            assertProtocolSanitized(
                    PlanExecutionContextCompositionStage
                            .POST_CONFIRM_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postConfirmContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null,
                    () -> continueReserved(
                            regressed,
                            reserved,
                            exactLease));
            assertDirectConfirmTrace(regressed);
        }

        ComposeHarness successfulButReserved = confirmHarness(
                reserved,
                exactLease,
                verified,
                PersistenceResult.found(reserved),
                PersistenceResult.applied(expected));
        assertPostConfirmReconciliationProtocol(
                successfulButReserved,
                reserved,
                exactLease);

        ComposeHarness successfulButDifferentConfirmer = confirmHarness(
                reserved,
                exactLease,
                verified,
                PersistenceResult.found(
                        new io.paperagent.v2.persistence
                                .PersistedPlanExecutionContextConfirmed(
                                        reserved,
                                        "different-owner-" + SECRET,
                                        exactLease.fencingToken() + 1,
                                        FINGERPRINT)),
                PersistenceResult.applied(expected));
        assertPostConfirmReconciliationProtocol(
                successfulButDifferentConfirmer,
                reserved,
                exactLease);

        ComposeHarness stableRejected = confirmHarness(
                reserved,
                exactLease,
                verified,
                PersistenceResult.found(reserved),
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"));
        var persistenceRejected = assertInstanceOf(
                PlanExecutionContextPersistenceRejected.class,
                continueReserved(
                        stableRejected,
                        reserved,
                        exactLease));
        assertEquals(
                PlanExecutionContextCompositionStage.CONFIRM,
                persistenceRejected.stage());
        assertDirectConfirmTrace(stableRejected);

        for (Object responseLoss : List.of(
                NULL,
                new IllegalStateException("reserved-" + SECRET))) {
            ComposeHarness indeterminate = confirmHarness(
                    reserved,
                    exactLease,
                    verified,
                    PersistenceResult.found(reserved),
                    responseLoss);
            var retry = assertInstanceOf(
                    PlanExecutionContextRetryRequired.class,
                    continueReserved(
                            indeterminate,
                            reserved,
                            exactLease));
            assertEquals(
                    PlanExecutionContextCompositionStage.CONFIRM,
                    retry.stage());
            assertEquals(
                    PlanExecutionContextRetryReason
                            .CONFIRMATION_INDETERMINATE,
                    retry.retryReason());
            assertDirectConfirmTrace(indeterminate);
        }
    }

    @Test
    void confirmDelegateThenThrowReconcilesFromMandatoryInspection() {
        var exactSpec = spec("confirm-delegate-then-throw");
        var exactLease = lease(attempt(), 137);
        var reserved = expectedReservation(exactSpec, exactLease);
        var verified = verifiedWorkspace(exactSpec, FINGERPRINT);
        var concurrent =
                new io.paperagent.v2.persistence
                        .PersistedPlanExecutionContextConfirmed(
                                reserved,
                                "delegate-winner-" + SECRET,
                                exactLease.fencingToken() + 1,
                                FINGERPRINT);
        AtomicInteger sideEffect = new AtomicInteger();
        RuntimeException responseLoss =
                new IllegalStateException("confirm-delegate-" + SECRET);
        ComposeHarness harness = confirmHarness(
                reserved,
                exactLease,
                verified,
                PersistenceResult.found(concurrent),
                actionThenThrow(
                        sideEffect::incrementAndGet,
                        responseLoss));

        var ready = assertInstanceOf(
                PlanExecutionContextReady.class,
                continueReserved(harness, reserved, exactLease));

        assertEquals(1, sideEffect.get());
        assertEquals(
                PlanExecutionContextCompositionResolution
                        .RECONCILED_AFTER_RESPONSE_LOSS,
                ready.resolution());
        assertSame(concurrent, ready.persistedContext());
        assertSame(verified, ready.verifiedWorkspace());
        assertFalse(ready.toString().contains(SECRET));
        assertDirectConfirmTrace(harness);
        assertFalse(Arrays.stream(
                        DefaultPlanExecutionContextComposer.class
                                .getDeclaredFields())
                .anyMatch(field -> field.getType()
                        .equals(DefaultPlanExecutionContextComposer
                                .Captured.class)));
    }

    private static ComposeHarness confirmHarness(
            io.paperagent.v2.persistence
                    .PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease,
            io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization
                    verified,
            Object postContext,
            Object confirmResult) {
        return harness(
                script(),
                script(postContext),
                script(),
                script(),
                script(verified),
                script(),
                script(confirmResult));
    }

    private static void assertDirectConfirmTrace(ComposeHarness harness) {
        assertEquals(
                List.of(
                        "workspace.inspect",
                        "context.confirm",
                        "context.inspect"),
                harness.trace());
        assertEquals(1, harness.workspace().inspectCalls.get());
        assertEquals(0, harness.workspace().materializeCalls.get());
        assertEquals(1, harness.contexts().confirmCalls.get());
        assertEquals(1, harness.contexts().inspectCalls.get());
        assertEquals(0, harness.contexts().reserveCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
    }

    private static void assertPostConfirmReconciliationProtocol(
            ComposeHarness harness,
            io.paperagent.v2.persistence
                    .PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease) {
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage
                        .POST_CONFIRM_CONTEXT_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postConfirmContextInspectResult.value",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        harness,
                        reserved,
                        lease));
        assertDirectConfirmTrace(harness);
    }

    private static PlanExecutionContextCompositionOutcome continueReserved(
            ComposeHarness harness,
            io.paperagent.v2.persistence
                    .PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease) {
        return harness.composer()
                .continueAfterReservedOrConfirmedAuthority(
                        DefaultPlanExecutionContextComposer
                                .ContextObservation.reserved(reserved),
                        lease);
    }

    private static ComposeHarness workspaceHarness(
            List<Object> inspections,
            List<Object> materializations) {
        return harness(
                script(),
                script(),
                script(),
                script(),
                inspections,
                materializations);
    }

    private static ComposeHarness confirmedWorkspaceHarness(
            io.paperagent.v2.persistence
                    .PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease,
            ContentHash fingerprint,
            List<Object> inspections,
            List<Object> materializations) {
        var confirmed = expectedConfirmation(
                reserved,
                lease,
                fingerprint);
        return harness(
                script(),
                script(PersistenceResult.found(confirmed)),
                script(),
                script(),
                inspections,
                materializations,
                script(PersistenceResult.applied(confirmed)));
    }

    private static WorkspaceException missingWorkspace() {
        return new WorkspaceException(
                WorkspaceErrorCode.WORKSPACE_NOT_FOUND,
                "inspectMaterialization");
    }

    private static void assertWorkspaceMaterializeTrace(
            ComposeHarness harness) {
        assertEquals(
                List.of(
                        "workspace.inspect",
                        "workspace.materialize",
                        "workspace.inspect"),
                harness.trace());
        assertEquals(2, harness.workspace().inspectCalls.get());
        assertEquals(1, harness.workspace().materializeCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
        assertEquals(0, harness.contexts().confirmCalls.get());
    }

    private static void assertWorkspaceMaterializeAndConfirmTrace(
            ComposeHarness harness) {
        assertEquals(
                List.of(
                        "workspace.inspect",
                        "workspace.materialize",
                        "workspace.inspect",
                        "context.confirm",
                        "context.inspect"),
                harness.trace());
        assertEquals(2, harness.workspace().inspectCalls.get());
        assertEquals(1, harness.workspace().materializeCalls.get());
        assertEquals(1, harness.contexts().confirmCalls.get());
        assertEquals(1, harness.contexts().inspectCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
    }

    private static void assertComposeMaterializeTraceAndZeroConfirm(
            ComposeHarness harness,
            io.paperagent.v2.contracts.WorkspaceMaterializationSpec spec) {
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "lease.acquire",
                        "execution.inspect",
                        "context.inspect",
                        "workspace.inspect",
                        "workspace.materialize",
                        "workspace.inspect"),
                harness.trace());
        assertEquals(
                List.of(spec, spec),
                harness.workspace().inspectedSpecs);
        assertEquals(
                List.of(spec),
                harness.workspace().materializedSpecs);
        assertEquals(2, harness.workspace().inspectCalls.get());
        assertEquals(1, harness.workspace().materializeCalls.get());
        assertEquals(0, harness.contexts().confirmCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
    }

    private static void assertComposeMaterializeAndConfirmTrace(
            ComposeHarness harness,
            io.paperagent.v2.contracts.WorkspaceMaterializationSpec spec) {
        assertEquals(
                List.of(
                        "execution.inspect",
                        "context.inspect",
                        "lease.acquire",
                        "execution.inspect",
                        "context.inspect",
                        "workspace.inspect",
                        "workspace.materialize",
                        "workspace.inspect",
                        "context.confirm",
                        "context.inspect"),
                harness.trace());
        assertEquals(
                List.of(spec, spec),
                harness.workspace().inspectedSpecs);
        assertEquals(
                List.of(spec),
                harness.workspace().materializedSpecs);
        assertEquals(2, harness.workspace().inspectCalls.get());
        assertEquals(1, harness.workspace().materializeCalls.get());
        assertEquals(1, harness.contexts().confirmCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
    }

    private static void assertNoSecret(Throwable throwable) {
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));
        assertFalse(throwable.getMessage().contains(SECRET));
        assertFalse(throwable.toString().contains(SECRET));
        assertFalse(stack.toString().contains(SECRET));
    }

    private static void assertPostMaterializeReconciliationProtocol(
            ComposeHarness harness,
            io.paperagent.v2.persistence
                    .PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease) {
        assertProtocolSanitized(
                PlanExecutionContextCompositionStage
                        .POST_MATERIALIZE_WORKSPACE_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postMaterializeWorkspaceInspectResult.value",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                null,
                () -> continueReserved(
                        harness,
                        reserved,
                        lease));
        assertWorkspaceMaterializeTrace(harness);
    }

    private static PlanExecutionContextCompositionRequest compositionRequest(
            io.paperagent.v2.contracts.WorkspaceMaterializationSpec spec,
            PlanExecutionContextLeaseAttempt leaseAttempt) {
        return new PlanExecutionContextCompositionRequest(
                PLAN_ID,
                Optional.of(spec),
                Optional.of(leaseAttempt));
    }

    private static io.paperagent.v2.persistence
            .PersistedPlanExecutionContextReserved expectedReservation(
                    io.paperagent.v2.contracts
                            .WorkspaceMaterializationSpec spec,
                    LeaseRecord lease) {
        return new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        PLAN_ID,
                        spec,
                        lease.ownerId(),
                        lease.fencingToken());
    }

    private static io.paperagent.v2.persistence
            .PersistedPlanExecutionContextConfirmed confirmationFor(
                    io.paperagent.v2.persistence
                            .PersistedPlanExecutionContextReserved
                                    reservation) {
        return new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed(
                        reservation,
                        "confirmation-owner-" + SECRET,
                        reservation.fencingToken() + 1,
                        FINGERPRINT);
    }

    private static io.paperagent.v2.persistence
            .PersistedPlanExecutionContextConfirmed expectedConfirmation(
                    io.paperagent.v2.persistence
                            .PersistedPlanExecutionContextReserved
                                    reservation,
                    LeaseRecord lease,
                    ContentHash fingerprint) {
        return new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed(
                        reservation,
                        lease.ownerId(),
                        lease.fencingToken(),
                        fingerprint);
    }

    private static ComposeHarness reserveHarness(
            io.paperagent.v2.persistence.PersistedExecutionStartCommitted
                    committed,
            io.paperagent.v2.contracts.WorkspaceMaterializationSpec spec,
            PlanExecutionContextLeaseAttempt leaseAttempt,
            LeaseRecord lease,
            Object postReserveContext,
            Object reserveResult) {
        return harness(
                script(
                        PersistenceResult.found(committed),
                        PersistenceResult.found(committed)),
                script(
                        contextNone(),
                        contextNone(),
                        postReserveContext),
                script(PersistenceResult.applied(lease)),
                script(reserveResult),
                script(verifiedWorkspace(
                        spec,
                        FINGERPRINT)));
    }

    private static void assertPostReserveTraceAndZeroForbidden(
            ComposeHarness harness) {
        List<String> prefix = List.of(
                "execution.inspect",
                "context.inspect",
                "lease.acquire",
                "execution.inspect",
                "context.inspect",
                "context.reserve",
                "context.inspect");
        List<String> expectedTrace = new ArrayList<>(prefix);
        if (harness.workspace().inspectCalls.get() != 0) {
            expectedTrace.add("workspace.inspect");
        }
        if (harness.contexts().confirmCalls.get() != 0) {
            expectedTrace.add("context.confirm");
            expectedTrace.add("context.inspect");
        }
        assertEquals(
                prefix,
                harness.trace().subList(0, prefix.size()));
        assertEquals(expectedTrace, harness.trace());
        assertEquals(2, harness.execution().inspectCalls.get());
        assertEquals(
                3 + harness.contexts().confirmCalls.get(),
                harness.contexts().inspectCalls.get());
        assertEquals(1, harness.leases().acquireCalls.get());
        assertEquals(0, harness.leases().renewCalls.get());
        assertEquals(0, harness.leases().releaseCalls.get());
        assertEquals(0, harness.leases().findCalls.get());
        assertEquals(1, harness.contexts().reserveCalls.get());
        assertEquals(
                harness.trace().contains("context.confirm") ? 1 : 0,
                harness.contexts().confirmCalls.get());
        assertEquals(
                harness.trace().contains("workspace.inspect") ? 1 : 0,
                harness.workspace().inspectCalls.get());
        assertEquals(0, harness.workspace().materializeCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
    }

    private static ComposeHarness harness(
            List<Object> executionInspections,
            List<Object> contextInspections,
            List<Object> acquisitions,
            List<Object> workspaceInspections) {
        return harness(
                executionInspections,
                contextInspections,
                acquisitions,
                script(),
                workspaceInspections);
    }

    private static ComposeHarness harness(
            List<Object> executionInspections,
            List<Object> contextInspections,
            List<Object> acquisitions,
            List<Object> reservations,
            List<Object> workspaceInspections) {
        return harness(
                executionInspections,
                contextInspections,
                acquisitions,
                reservations,
                workspaceInspections,
                script(),
                script());
    }

    private static ComposeHarness harness(
            List<Object> executionInspections,
            List<Object> contextInspections,
            List<Object> acquisitions,
            List<Object> reservations,
            List<Object> workspaceInspections,
            List<Object> workspaceMaterializations) {
        return harness(
                executionInspections,
                contextInspections,
                acquisitions,
                reservations,
                workspaceInspections,
                workspaceMaterializations,
                script());
    }

    private static ComposeHarness harness(
            List<Object> executionInspections,
            List<Object> contextInspections,
            List<Object> acquisitions,
            List<Object> reservations,
            List<Object> workspaceInspections,
            List<Object> workspaceMaterializations,
            List<Object> confirmations) {
        List<String> trace = new ArrayList<>();
        var execution =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedExecutionStartRecoveryRepository(
                                executionInspections,
                                trace);
        var contexts =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedPlanExecutionContextRepository(
                                contextInspections,
                                reservations,
                                confirmations,
                                trace);
        var leases =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedLeaseRepository(acquisitions, trace);
        var workspace =
                new PlanExecutionContextCompositionTestFixtures
                        .ScriptedWorkspacePort(
                                workspaceInspections,
                                workspaceMaterializations,
                                trace);
        return new ComposeHarness(
                new DefaultPlanExecutionContextComposer(
                        execution,
                        contexts,
                        leases,
                        workspace),
                execution,
                contexts,
                leases,
                workspace,
                trace);
    }

    private static List<Object> script(Object... values) {
        return List.of(values);
    }

    private static PersistenceResult<?> contextNone() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.NOT_FOUND,
                "planExecutionContext");
    }

    private static PlanExecutionContextCompositionRequest emptyRequest(
            io.paperagent.v2.contracts.PlanId planId) {
        return new PlanExecutionContextCompositionRequest(
                planId,
                Optional.empty(),
                Optional.empty());
    }

    private static PlanExecutionContextLeaseAttempt attempt() {
        return new PlanExecutionContextLeaseAttempt(
                "owner-" + SECRET,
                "token-" + SECRET,
                Instant.parse("2026-07-25T01:00:00Z"));
    }

    private static LeaseRecord lease(
            PlanExecutionContextLeaseAttempt attempt,
            long fence) {
        return new LeaseRecord(
                PLAN_ID,
                attempt.leaseOwnerId(),
                attempt.leaseToken(),
                fence,
                Instant.parse("2026-07-25T00:00:00Z"),
                attempt.leaseExpiresAt());
    }

    private static void assertZeroAfterExecution(ComposeHarness harness) {
        assertEquals(0, harness.contexts().inspectCalls.get());
        assertZeroMutationsAndWorkspace(harness);
    }

    private static void assertZeroMutationsAndWorkspace(
            ComposeHarness harness) {
        assertEquals(0, harness.leases().acquireCalls.get());
        assertEquals(0, harness.leases().renewCalls.get());
        assertEquals(0, harness.leases().releaseCalls.get());
        assertEquals(0, harness.leases().findCalls.get());
        assertEquals(0, harness.contexts().reserveCalls.get());
        assertEquals(0, harness.contexts().confirmCalls.get());
        assertEquals(0, harness.workspace().inspectCalls.get());
        assertEquals(0, harness.workspace().materializeCalls.get());
        assertEquals(0, harness.workspace().unexpectedCalls.get());
    }

    private static DefaultPlanExecutionContextComposer
            .ExecutionStartObservation classifyExecution(
                    DefaultPlanExecutionContextComposer.Captured captured,
                    String path) {
        return DefaultPlanExecutionContextComposer
                .classifyExecutionInspection(
                        PLAN_ID,
                        captured,
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        path,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
    }

    private static DefaultPlanExecutionContextComposer.ContextObservation
            classifyContext(
                    DefaultPlanExecutionContextComposer.Captured captured,
                    String path) {
        return DefaultPlanExecutionContextComposer
                .classifyContextInspection(
                        PLAN_ID,
                        captured,
                        PlanExecutionContextCompositionStage
                                .INITIAL_CONTEXT_INSPECT,
                        path,
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
    }

    private static DefaultPlanExecutionContextComposer.Captured captured(
            Object value) {
        return new DefaultPlanExecutionContextComposer.Captured(value, null);
    }

    private static DefaultPlanExecutionContextComposer.Captured thrown(
            RuntimeException exception) {
        return new DefaultPlanExecutionContextComposer.Captured(
                null,
                exception);
    }

    private static void assertMutationFailureRejected(
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String basePath,
            PlanExecutionContextLeaseDisposition disposition,
            org.junit.jupiter.api.function.Executable executable) {
        assertProtocolSanitized(
                stage,
                code,
                basePath + ".failure",
                disposition,
                null,
                executable);
    }

    private static <T> void assertCanonicalMutationFailures(
            List<PersistenceFailure> canonicalFailures,
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String basePath,
            Function<
                    PersistenceFailure,
                    DefaultPlanExecutionContextComposer
                            .PersistenceMutationObservation<T>> classifier) {
        for (PersistenceFailure canonical : canonicalFailures) {
            assertEquals(canonical, classifier.apply(canonical).failure());
            assertMutationFailureRejected(
                    stage,
                    code,
                    basePath,
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    () -> classifier.apply(failure(
                            canonical.code(),
                            canonical.path() + "." + SECRET)));
            assertMutationFailureRejected(
                    stage,
                    code,
                    basePath,
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    () -> classifier.apply(failure(
                            PersistenceErrorCode
                                    .EXECUTION_RECOVERY_ADVANCED_STATE,
                            canonical.path())));
        }
    }

    private static void assertProtocolSanitized(
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            PlanExecutionContextLeaseDisposition disposition,
            RuntimeException original,
            org.junit.jupiter.api.function.Executable executable) {
        var protocol = assertThrows(
                PlanExecutionContextCompositionProtocolException.class,
                executable);
        assertEquals(PLAN_ID, protocol.planId());
        assertEquals(stage, protocol.stage());
        assertEquals(code, protocol.code());
        assertEquals(path, protocol.path());
        assertEquals(disposition, protocol.leaseDisposition());
        if (original == null) {
            assertNull(protocol.getCause());
        } else {
            assertNotSame(original, protocol.getCause());
            assertNull(protocol.getCause().getCause());
            assertEquals(0, protocol.getCause().getSuppressed().length);
            assertEquals(0, protocol.getCause().getStackTrace().length);
            assertFalse(protocol.getCause().getMessage().contains(SECRET));
            assertFalse(protocol.getCause().toString().contains(SECRET));
        }
        StringWriter trace = new StringWriter();
        protocol.printStackTrace(new PrintWriter(trace));
        assertFalse(protocol.getMessage().contains(SECRET));
        assertFalse(protocol.toString().contains(SECRET));
        assertFalse(trace.toString().contains(SECRET));
    }

    private static void assertClassifierProtocol(
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(
                PlanExecutionContextCompositionProtocolException.class,
                executable);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }

    private enum PersistenceOutcomeFactory {
        APPLIED {
            @Override
            <T> PersistenceResult<T> result(T value) {
                return PersistenceResult.applied(value);
            }
        },
        REPLAYED {
            @Override
            <T> PersistenceResult<T> result(T value) {
                return PersistenceResult.replayed(value);
            }
        };

        abstract <T> PersistenceResult<T> result(T value);
    }

    private record ComposeHarness(
            DefaultPlanExecutionContextComposer composer,
            PlanExecutionContextCompositionTestFixtures
                    .ScriptedExecutionStartRecoveryRepository execution,
            PlanExecutionContextCompositionTestFixtures
                    .ScriptedPlanExecutionContextRepository contexts,
            PlanExecutionContextCompositionTestFixtures
                    .ScriptedLeaseRepository leases,
            PlanExecutionContextCompositionTestFixtures
                    .ScriptedWorkspacePort workspace,
            List<String> trace) {
    }

    private record ValidationCase(
            Object contextResult,
            PlanExecutionContextCompositionRequest request,
            PlanExecutionContextCompositionValidationCode code,
            String path) {
    }

    private record ContinuationCase(
            Object initialContext,
            Object postContext,
            PlanExecutionContextCompositionRequest request) {
    }

    private record AcquireCase(
            Object acquireResult,
            PlanExecutionContextLeaseDisposition disposition) {
    }

    private record MalformedAcquireCase(
            Object acquireResult,
            PlanExecutionContextCompositionProtocolCode code,
            String path) {
    }

    private record ReserveObservationCase(
            Object scriptedResult,
            boolean exactSuccess) {
    }

    private record PostReserveContextFailureCase(
            Object contextResult,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            RuntimeException cause) {
    }

    private record PostMaterializeProtocolCase(
            Object postInspection,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            RuntimeException cause) {
    }

    private record ConfirmResolutionCase(
            Object confirmResult,
            io.paperagent.v2.persistence
                    .PersistedPlanExecutionContextConfirmed postConfirmation,
            PlanExecutionContextCompositionResolution resolution) {
    }

    private record PostConfirmSemanticCase(
            Object postContext,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            RuntimeException cause) {
    }

    private record MalformedConfirmCase(
            Object confirmResult,
            PlanExecutionContextCompositionProtocolCode code,
            String path) {
    }

    private record MalformedReserveCase(
            Object reserveResult,
            PlanExecutionContextCompositionProtocolCode code,
            String path) {
    }

    private static final class FirstCaptureException
            extends IllegalStateException {
        private FirstCaptureException(String message) {
            super(message);
        }
    }

    private static final class SecondCaptureException
            extends IllegalStateException {
        private SecondCaptureException(String message) {
            super(message);
        }
    }

    private static Map<
            PlanExecutionContextCompositionStage,
            List<PersistenceFailure>> canonicalPersistenceFailures() {
        return Map.ofEntries(
                Map.entry(
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        List.of(executionNotFound(), executionPartial())),
                Map.entry(
                        PlanExecutionContextCompositionStage
                                .INITIAL_CONTEXT_INSPECT,
                        List.of(contextPartial())),
                Map.entry(
                        PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                        acquireFailures()),
                Map.entry(
                        PlanExecutionContextCompositionStage
                                .POST_LEASE_EXECUTION_START_INSPECT,
                        List.of(executionNotFound(), executionPartial())),
                Map.entry(
                        PlanExecutionContextCompositionStage
                                .POST_LEASE_CONTEXT_INSPECT,
                        List.of(contextPartial())),
                Map.entry(
                        PlanExecutionContextCompositionStage.RESERVE,
                        reserveFailures()),
                Map.entry(
                        PlanExecutionContextCompositionStage
                                .POST_RESERVE_CONTEXT_INSPECT,
                        List.of(contextPartial())),
                Map.entry(
                        PlanExecutionContextCompositionStage.CONFIRM,
                        confirmFailures()),
                Map.entry(
                        PlanExecutionContextCompositionStage
                                .POST_CONFIRM_CONTEXT_INSPECT,
                        List.of(contextPartial())));
    }

    private static Set<PlanExecutionContextLeaseDisposition>
            persistenceDispositions(
                    PlanExecutionContextCompositionStage stage) {
        return switch (stage) {
            case INITIAL_EXECUTION_START_INSPECT,
                    INITIAL_CONTEXT_INSPECT ->
                    EnumSet.of(
                            PlanExecutionContextLeaseDisposition
                                    .NO_LEASE_ACTION);
            case LEASE_ACQUIRE -> EnumSet.of(
                    PlanExecutionContextLeaseDisposition.NOT_ACQUIRED);
            case POST_LEASE_EXECUTION_START_INSPECT,
                    POST_LEASE_CONTEXT_INSPECT ->
                    EnumSet.of(
                            PlanExecutionContextLeaseDisposition.NOT_ACQUIRED,
                            PlanExecutionContextLeaseDisposition
                                    .ACQUISITION_INDETERMINATE,
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            case RESERVE, POST_RESERVE_CONTEXT_INSPECT,
                    CONFIRM, POST_CONFIRM_CONTEXT_INSPECT ->
                    EnumSet.of(
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            default -> Set.of();
        };
    }

    private static PersistenceFailure wrongPersistenceFamily(
            PlanExecutionContextCompositionStage stage) {
        return switch (stage) {
            case INITIAL_EXECUTION_START_INSPECT,
                    POST_LEASE_EXECUTION_START_INSPECT ->
                    contextPartial();
            case INITIAL_CONTEXT_INSPECT,
                    POST_LEASE_CONTEXT_INSPECT,
                    POST_RESERVE_CONTEXT_INSPECT,
                    POST_CONFIRM_CONTEXT_INSPECT ->
                    executionNotFound();
            case LEASE_ACQUIRE -> contextPartial();
            case RESERVE -> executionNotFound();
            case CONFIRM -> failure(
                    PersistenceErrorCode.STALE_VERSION,
                    "request.expectedRevisionId");
            default -> secretNonCanonicalFailure();
        };
    }

    private static PlanExecutionContextCompositionStage expectedRetryStage(
            PlanExecutionContextRetryReason reason) {
        return switch (reason) {
            case RESERVATION_INDETERMINATE ->
                    PlanExecutionContextCompositionStage.RESERVE;
            case MATERIALIZATION_INDETERMINATE ->
                    PlanExecutionContextCompositionStage
                            .WORKSPACE_MATERIALIZE;
            case CONFIRMATION_INDETERMINATE ->
                    PlanExecutionContextCompositionStage.CONFIRM;
            case EXECUTION_START_NOT_COMMITTED ->
                    PlanExecutionContextCompositionStage
                            .INITIAL_EXECUTION_START_INSPECT;
        };
    }

    private static void assertRequired(
            org.junit.jupiter.api.function.Executable executable,
            String path) {
        assertValidation(
                executable,
                PlanExecutionContextCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                path);
    }

    private static void assertInvalidOutcome(
            org.junit.jupiter.api.function.Executable executable,
            String path) {
        assertValidation(
                executable,
                PlanExecutionContextCompositionValidationCode
                        .INVALID_OUTCOME_STATE,
                path);
    }

    private static void assertOutcome(
            boolean legal,
            org.junit.jupiter.api.function.Executable executable,
            String invalidPath) {
        if (legal) {
            assertDoesNotThrow(executable);
        } else {
            assertInvalidOutcome(executable, invalidPath);
        }
    }

    private static void assertValidation(
            org.junit.jupiter.api.function.Executable executable,
            PlanExecutionContextCompositionValidationCode code,
            String path) {
        PlanExecutionContextCompositionValidationException failure =
                assertThrows(
                        PlanExecutionContextCompositionValidationException
                                .class,
                        executable);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }
}
