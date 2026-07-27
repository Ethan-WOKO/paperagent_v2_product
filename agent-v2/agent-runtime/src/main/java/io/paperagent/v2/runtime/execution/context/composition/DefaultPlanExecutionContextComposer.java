package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.persistence.PlanExecutionContextSnapshot;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
import io.paperagent.v2.workspace.WorkspacePort;

import java.util.function.Predicate;
import java.util.function.Supplier;

public final class DefaultPlanExecutionContextComposer
        implements PlanExecutionContextComposer {
    private final ExecutionStartRecoveryRepository
            executionStartRecoveryRepository;
    private final PlanExecutionContextRepository
            planExecutionContextRepository;
    private final LeaseRepository leaseRepository;
    private final WorkspacePort workspacePort;

    public DefaultPlanExecutionContextComposer(
            ExecutionStartRecoveryRepository
                    executionStartRecoveryRepository,
            PlanExecutionContextRepository
                    planExecutionContextRepository,
            LeaseRepository leaseRepository,
            WorkspacePort workspacePort) {
        this.executionStartRecoveryRepository =
                PlanExecutionContextCompositionValues.required(
                        executionStartRecoveryRepository,
                        "planExecutionContextComposition"
                                + ".executionStartRecoveryRepository");
        this.planExecutionContextRepository =
                PlanExecutionContextCompositionValues.required(
                        planExecutionContextRepository,
                        "planExecutionContextComposition"
                                + ".planExecutionContextRepository");
        this.leaseRepository =
                PlanExecutionContextCompositionValues.required(
                        leaseRepository,
                        "planExecutionContextComposition.leaseRepository");
        this.workspacePort =
                PlanExecutionContextCompositionValues.required(
                        workspacePort,
                        "planExecutionContextComposition.workspacePort");
    }

    @Override
    public PlanExecutionContextCompositionOutcome compose(
            PlanExecutionContextCompositionRequest request) {
        PlanExecutionContextCompositionRequest requiredRequest =
                PlanExecutionContextCompositionValues.required(
                request,
                "planExecutionContextComposition.request");
        PlanId planId = requiredRequest.planId();
        ExecutionStartObservation initialExecution =
                classifyExecutionInspection(
                        planId,
                        capture(() -> executionStartRecoveryRepository
                                .inspect(planId)),
                        PlanExecutionContextCompositionStage
                                .INITIAL_EXECUTION_START_INSPECT,
                        "planExecutionContextComposition"
                                + ".initialExecutionStartInspectResult",
                        PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
        if (initialExecution.state()
                != ExecutionStartState.COMMITTED) {
            return initialExecutionOutcome(planId, initialExecution);
        }

        ContextObservation initialContext = classifyContextInspection(
                planId,
                capture(() -> planExecutionContextRepository.inspect(planId)),
                PlanExecutionContextCompositionStage.INITIAL_CONTEXT_INSPECT,
                "planExecutionContextComposition"
                        + ".initialContextInspectResult",
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
        PersistedExecutionStartCommitted committed =
                initialExecution.committed();
        if (committed.bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .isEmpty()) {
            return composeSourceLess(
                    requiredRequest,
                    initialContext);
        }
        if (initialContext.state() == ContextState.PARTIAL) {
            return new PlanExecutionContextPersistenceRejected(
                    planId,
                    PlanExecutionContextCompositionStage
                            .INITIAL_CONTEXT_INSPECT,
                    initialContext.failure(),
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
        }

        WorkspaceMaterializationSpec authoritativeSpec =
                validateSourceBackedRequest(
                        requiredRequest,
                        committed,
                        initialContext);
        if (initialContext.state() == ContextState.CONFIRMED) {
            return observeConfirmedWorkspace(
                    initialContext.confirmed(),
                    PlanExecutionContextCompositionResolution
                            .OBSERVED_CONFIRMED,
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
        }

        PlanExecutionContextLeaseAttempt attempt =
                requiredRequest.leaseAttempt().orElseThrow(() ->
                        PlanExecutionContextCompositionValues
                                .validationFailure(
                                        PlanExecutionContextCompositionValidationCode
                                                .REQUIRED_VALUE_MISSING,
                                        "planExecutionContextComposition"
                                                + ".request.leaseAttempt"));
        return acquireAndReinspect(
                requiredRequest,
                committed,
                initialContext,
                authoritativeSpec,
                attempt);
    }

    private static PlanExecutionContextCompositionOutcome
            initialExecutionOutcome(
                    PlanId planId,
                    ExecutionStartObservation observation) {
        return switch (observation.state()) {
            case READY -> new PlanExecutionContextRetryRequired(
                    planId,
                    PlanExecutionContextCompositionStage
                            .INITIAL_EXECUTION_START_INSPECT,
                    PlanExecutionContextRetryReason
                            .EXECUTION_START_NOT_COMMITTED,
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
            case ADVANCED -> new PlanExecutionContextAdvancedUnsupported(
                    planId,
                    PlanExecutionContextCompositionStage
                            .INITIAL_EXECUTION_START_INSPECT,
                    observation.failure(),
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
            case PARTIAL, NOT_FOUND ->
                    new PlanExecutionContextPersistenceRejected(
                            planId,
                            PlanExecutionContextCompositionStage
                                    .INITIAL_EXECUTION_START_INSPECT,
                            observation.failure(),
                            PlanExecutionContextLeaseDisposition
                                    .NO_LEASE_ACTION);
            case COMMITTED -> throw new IllegalStateException(
                    "committed execution must continue composition");
        };
    }

    private static PlanExecutionContextCompositionOutcome composeSourceLess(
            PlanExecutionContextCompositionRequest request,
            ContextObservation context) {
        PlanId planId = request.planId();
        if (context.state() == ContextState.PARTIAL) {
            return new PlanExecutionContextPersistenceRejected(
                    planId,
                    PlanExecutionContextCompositionStage
                            .INITIAL_CONTEXT_INSPECT,
                    context.failure(),
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
        }
        if (context.state() != ContextState.NONE) {
            throw protocol(
                    planId,
                    PlanExecutionContextCompositionStage
                            .INITIAL_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".initialContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                    null);
        }
        if (request.proposedMaterializationSpec().isPresent()) {
            throw PlanExecutionContextCompositionValues.inconsistentRequest(
                    "planExecutionContextComposition.request"
                            + ".proposedMaterializationSpec");
        }
        if (request.leaseAttempt().isPresent()) {
            throw PlanExecutionContextCompositionValues.inconsistentRequest(
                    "planExecutionContextComposition.request.leaseAttempt");
        }
        return new PlanExecutionContextNotRequired(
                planId,
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
    }

    private static WorkspaceMaterializationSpec
            validateSourceBackedRequest(
                    PlanExecutionContextCompositionRequest request,
                    PersistedExecutionStartCommitted committed,
                    ContextObservation context) {
        var storedSource = committed.bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .orElseThrow();
        if (context.state() == ContextState.NONE) {
            WorkspaceMaterializationSpec proposed =
                    request.proposedMaterializationSpec().orElseThrow(() ->
                            PlanExecutionContextCompositionValues
                                    .validationFailure(
                                            PlanExecutionContextCompositionValidationCode
                                                    .REQUIRED_VALUE_MISSING,
                                            "planExecutionContextComposition"
                                                    + ".request"
                                                    + ".proposedMaterializationSpec"));
            if (!proposed.sourceProjectVersion().equals(storedSource)) {
                throw PlanExecutionContextCompositionValues
                        .inconsistentRequest(
                                "planExecutionContextComposition.request"
                                        + ".proposedMaterializationSpec");
            }
            requireAttempt(request);
            return proposed;
        }

        WorkspaceMaterializationSpec persistedSpec =
                context.state() == ContextState.RESERVED
                        ? context.reserved().materializationSpec()
                        : context.confirmed().materializationSpec();
        if (!persistedSpec.sourceProjectVersion().equals(storedSource)) {
            throw protocol(
                    request.planId(),
                    PlanExecutionContextCompositionStage
                            .INITIAL_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".initialContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION,
                    null);
        }
        if (request.proposedMaterializationSpec()
                .filter(proposed -> !proposed.equals(persistedSpec))
                .isPresent()) {
            throw PlanExecutionContextCompositionValues.inconsistentRequest(
                    "planExecutionContextComposition.request"
                            + ".proposedMaterializationSpec");
        }
        if (context.state() == ContextState.RESERVED) {
            requireAttempt(request);
        } else if (request.leaseAttempt().isPresent()) {
            throw PlanExecutionContextCompositionValues.inconsistentRequest(
                    "planExecutionContextComposition.request.leaseAttempt");
        }
        return persistedSpec;
    }

    private static void requireAttempt(
            PlanExecutionContextCompositionRequest request) {
        if (request.leaseAttempt().isEmpty()) {
            throw PlanExecutionContextCompositionValues.validationFailure(
                    PlanExecutionContextCompositionValidationCode
                            .REQUIRED_VALUE_MISSING,
                    "planExecutionContextComposition.request.leaseAttempt");
        }
    }

    private PlanExecutionContextCompositionOutcome
            acquireAndReinspect(
                    PlanExecutionContextCompositionRequest request,
                    PersistedExecutionStartCommitted initialCommitted,
                    ContextObservation initialContext,
                    WorkspaceMaterializationSpec authoritativeSpec,
                    PlanExecutionContextLeaseAttempt attempt) {
        PlanId planId = request.planId();
        Captured acquire = capture(() -> leaseRepository.acquire(
                planId,
                attempt.leaseOwnerId(),
                attempt.leaseToken(),
                attempt.leaseExpiresAt()));
        Captured postExecutionCapture = capture(() ->
                executionStartRecoveryRepository.inspect(planId));
        Captured postContextCapture = capture(() ->
                planExecutionContextRepository.inspect(planId));
        PlanExecutionContextLeaseDisposition disposition =
                acquisitionDisposition(planId, attempt, acquire);

        ExecutionStartObservation postExecution =
                classifyExecutionInspection(
                        planId,
                        postExecutionCapture,
                        PlanExecutionContextCompositionStage
                                .POST_LEASE_EXECUTION_START_INSPECT,
                        "planExecutionContextComposition"
                                + ".postLeaseExecutionStartInspectResult",
                        disposition);
        validatePostLeaseExecutionAuthority(
                planId,
                initialCommitted,
                postExecution,
                disposition);

        ContextObservation postContext = classifyContextInspection(
                planId,
                postContextCapture,
                PlanExecutionContextCompositionStage
                        .POST_LEASE_CONTEXT_INSPECT,
                        "planExecutionContextComposition"
                                + ".postLeaseContextInspectResult",
                disposition);
        validatePostLeaseContext(
                planId,
                initialContext,
                postContext,
                authoritativeSpec,
                disposition);

        PersistenceMutationObservation<LeaseRecord> lease =
                classifyLeaseResult(
                        planId,
                        attempt,
                        acquire,
                        disposition);

        PlanExecutionContextCompositionOutcome executionOutcome =
                postLeaseExecutionOutcome(
                        planId,
                        postExecution,
                        disposition);
        if (executionOutcome != null) {
            return executionOutcome;
        }
        if (postContext.state() == ContextState.PARTIAL) {
            return new PlanExecutionContextPersistenceRejected(
                    planId,
                    PlanExecutionContextCompositionStage
                            .POST_LEASE_CONTEXT_INSPECT,
                    postContext.failure(),
                    disposition);
        }
        if (postContext.state() == ContextState.CONFIRMED) {
            return observeConfirmedWorkspace(
                    postContext.confirmed(),
                    PlanExecutionContextCompositionResolution
                            .OBSERVED_CONCURRENT_CONFIRMATION,
                    disposition);
        }
        return switch (lease.state()) {
            case REJECTED ->
                    new PlanExecutionContextPersistenceRejected(
                            planId,
                            PlanExecutionContextCompositionStage
                                    .LEASE_ACQUIRE,
                            lease.failure(),
                            PlanExecutionContextLeaseDisposition.NOT_ACQUIRED);
            case NULL_RESULT -> throw protocol(
                    planId,
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    PlanExecutionContextCompositionProtocolCode
                            .NULL_COLLABORATOR_RESULT,
                    "planExecutionContextComposition.leaseAcquireResult",
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null);
            case THROWN -> throw protocol(
                    planId,
                    PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                    PlanExecutionContextCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    "planExecutionContextComposition.leaseAcquireResult",
                    PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    lease.exception());
            case APPLIED, REPLAYED ->
                    continueAfterAcquiredUnconfirmedAuthority(
                            postExecution.committed(),
                            postContext,
                            authoritativeSpec,
                            lease.value());
        };
    }

    private static void validatePostLeaseExecutionAuthority(
                    PlanId planId,
                    PersistedExecutionStartCommitted initialCommitted,
                    ExecutionStartObservation observation,
                    PlanExecutionContextLeaseDisposition disposition) {
        switch (observation.state()) {
            case COMMITTED -> {
                if (!observation.committed().equals(initialCommitted)) {
                    throw protocol(
                            planId,
                            PlanExecutionContextCompositionStage
                                    .POST_LEASE_EXECUTION_START_INSPECT,
                            PlanExecutionContextCompositionProtocolCode
                                    .INCONSISTENT_EXECUTION_START_AUTHORITY,
                            "planExecutionContextComposition"
                                    + ".postLeaseExecutionStartInspectResult"
                                    + ".value",
                                    disposition,
                                    null);
                }
            }
            case READY -> throw protocol(
                    planId,
                    PlanExecutionContextCompositionStage
                            .POST_LEASE_EXECUTION_START_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postLeaseExecutionStartInspectResult.value",
                    disposition,
                    null);
            case ADVANCED, PARTIAL, NOT_FOUND -> {
            }
        }
    }

    private static PlanExecutionContextCompositionOutcome
            postLeaseExecutionOutcome(
                    PlanId planId,
                    ExecutionStartObservation observation,
                    PlanExecutionContextLeaseDisposition disposition) {
        return switch (observation.state()) {
            case COMMITTED -> null;
            case READY -> throw new IllegalStateException(
                    "ready execution authority must fail semantic validation");
            case ADVANCED -> new PlanExecutionContextAdvancedUnsupported(
                    planId,
                    PlanExecutionContextCompositionStage
                            .POST_LEASE_EXECUTION_START_INSPECT,
                    observation.failure(),
                    disposition);
            case PARTIAL, NOT_FOUND ->
                    new PlanExecutionContextPersistenceRejected(
                            planId,
                            PlanExecutionContextCompositionStage
                                    .POST_LEASE_EXECUTION_START_INSPECT,
                            observation.failure(),
                            disposition);
        };
    }

    private static void validatePostLeaseContext(
            PlanId planId,
            ContextObservation initial,
            ContextObservation current,
            WorkspaceMaterializationSpec authoritativeSpec,
            PlanExecutionContextLeaseDisposition disposition) {
        if (initial.state() == ContextState.RESERVED
                && current.state() == ContextState.NONE) {
            throw inconsistentPostLeaseContext(planId, disposition);
        }
        if (current.state() == ContextState.RESERVED) {
            if (!current.reserved()
                            .materializationSpec()
                            .equals(authoritativeSpec)
                    || initial.state() == ContextState.RESERVED
                            && !current.reserved().equals(
                                    initial.reserved())) {
                throw inconsistentPostLeaseContext(planId, disposition);
            }
        }
        if (current.state() == ContextState.CONFIRMED) {
            if (!current.confirmed()
                            .materializationSpec()
                            .equals(authoritativeSpec)
                    || initial.state() == ContextState.RESERVED
                            && !current.confirmed()
                                    .reservation()
                                    .equals(initial.reserved())) {
                throw inconsistentPostLeaseContext(planId, disposition);
            }
        }
    }

    private static PlanExecutionContextCompositionProtocolException
            inconsistentPostLeaseContext(
                    PlanId planId,
                    PlanExecutionContextLeaseDisposition disposition) {
        return protocol(
                planId,
                PlanExecutionContextCompositionStage
                        .POST_LEASE_CONTEXT_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postLeaseContextInspectResult.value",
                disposition,
                null);
    }

    private PlanExecutionContextCompositionOutcome
            observeConfirmedWorkspace(
                    PersistedPlanExecutionContextConfirmed confirmed,
                    PlanExecutionContextCompositionResolution resolution,
                    PlanExecutionContextLeaseDisposition disposition) {
        Captured workspaceCapture = capture(() ->
                workspacePort.inspectMaterialization(
                        confirmed.materializationSpec()));
        WorkspaceObservation workspace = classifyWorkspaceInspection(
                confirmed.planId(),
                confirmed.materializationSpec(),
                workspaceCapture,
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                "planExecutionContextComposition.workspaceInspectResult",
                disposition);
        if (workspace.state() == WorkspaceState.REJECTED) {
            return new PlanExecutionContextWorkspaceRejected(
                    confirmed.planId(),
                    PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                    workspace.errorCode(),
                    disposition);
        }
        if (!workspace.verified()
                .sourceManifestFingerprint()
                .equals(confirmed.sourceManifestFingerprint())) {
            throw protocol(
                    confirmed.planId(),
                    PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_WORKSPACE_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".workspaceInspectResult.value",
                    disposition,
                    null);
        }
        return new PlanExecutionContextReady(
                resolution,
                confirmed,
                workspace.verified(),
                disposition);
    }

    static PlanExecutionContextLeaseDisposition acquisitionDisposition(
            PlanId planId,
            PlanExecutionContextLeaseAttempt attempt,
            Captured captured) {
        if (captured.exception() != null
                || !(captured.result()
                        instanceof PersistenceResult<?> result)
                || result.outcome() == PersistenceOutcome.FOUND
                || isMalformedSuccessfulLease(
                        planId,
                        attempt,
                        result)) {
            return PlanExecutionContextLeaseDisposition
                     .ACQUISITION_INDETERMINATE;
        }
        if (result.outcome() == PersistenceOutcome.REJECTED) {
            Object failure = result.failure().orElse(null);
            return failure instanceof PersistenceFailure typedFailure
                    && isLeaseAcquireFailure(typedFailure)
                    ? PlanExecutionContextLeaseDisposition.NOT_ACQUIRED
                    : PlanExecutionContextLeaseDisposition
                            .ACQUISITION_INDETERMINATE;
        }
        return PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY;
    }

    private static boolean isMalformedSuccessfulLease(
            PlanId planId,
            PlanExecutionContextLeaseAttempt attempt,
            PersistenceResult<?> result) {
        if (result.outcome() != PersistenceOutcome.APPLIED
                && result.outcome() != PersistenceOutcome.REPLAYED) {
            return false;
        }
        Object value = result.value().orElse(null);
        return !(value instanceof LeaseRecord lease)
                || !lease.planId().equals(planId)
                || !lease.ownerId().equals(attempt.leaseOwnerId())
                || !lease.leaseToken().equals(attempt.leaseToken())
                || !lease.expiresAt().equals(attempt.leaseExpiresAt());
    }

    PlanExecutionContextCompositionOutcome
            continueAfterAcquiredUnconfirmedAuthority(
                    PersistedExecutionStartCommitted committed,
                    ContextObservation context,
                    WorkspaceMaterializationSpec authoritativeSpec,
                    LeaseRecord lease) {
        if (context.state() == ContextState.RESERVED) {
            return continueAfterReservedOrConfirmedAuthority(
                    context,
                    lease);
        }
        if (context.state() != ContextState.NONE) {
            throw new IllegalStateException(
                    "only none or reserved authority may continue");
        }
        return reserveAndReinspect(
                committed,
                authoritativeSpec,
                lease);
    }

    private PlanExecutionContextCompositionOutcome reserveAndReinspect(
            PersistedExecutionStartCommitted committed,
            WorkspaceMaterializationSpec authoritativeSpec,
            LeaseRecord lease) {
        PlanExecutionContextReservationRequest request =
                reservationRequest(
                        committed,
                        authoritativeSpec,
                        lease);
        PersistedPlanExecutionContextReserved expected =
                new PersistedPlanExecutionContextReserved(
                        committed.planId(),
                        authoritativeSpec,
                        lease.ownerId(),
                        lease.fencingToken());
        Captured reserve = capture(() ->
                planExecutionContextRepository.reserve(request));
        Captured postReserveContextCapture = capture(() ->
                planExecutionContextRepository.inspect(committed.planId()));

        ContextObservation postReserveContext =
                classifyContextInspection(
                        committed.planId(),
                        postReserveContextCapture,
                        PlanExecutionContextCompositionStage
                                .POST_RESERVE_CONTEXT_INSPECT,
                        "planExecutionContextComposition"
                                + ".postReserveContextInspectResult",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        validatePostReserveContext(
                committed.planId(),
                postReserveContext,
                expected);

        PersistenceMutationObservation<
                PersistedPlanExecutionContextReserved> reserveObservation =
                classifyReserveResult(
                        committed.planId(),
                        expected,
                        reserve);
        if (postReserveContext.state() == ContextState.PARTIAL) {
            return new PlanExecutionContextPersistenceRejected(
                    committed.planId(),
                    PlanExecutionContextCompositionStage
                            .POST_RESERVE_CONTEXT_INSPECT,
                    postReserveContext.failure(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }
        if (postReserveContext.state() == ContextState.NONE) {
            return reconcileAbsentPostReserveContext(
                    committed.planId(),
                    reserveObservation);
        }
        validateSuccessfulReserveReconciliation(
                committed.planId(),
                postReserveContext,
                expected,
                reserveObservation);
        return continueAfterReservedOrConfirmedAuthority(
                postReserveContext,
                lease);
    }

    private static PlanExecutionContextReservationRequest reservationRequest(
            PersistedExecutionStartCommitted committed,
            WorkspaceMaterializationSpec authoritativeSpec,
            LeaseRecord lease) {
        var revision = committed.currentPlan().latestRevision();
        var startedCheckpoint =
                committed.executionStart().startedCheckpoint();
        return new PlanExecutionContextReservationRequest(
                committed.planId(),
                lease.leaseToken(),
                lease.fencingToken(),
                revision.id(),
                revision.number(),
                startedCheckpoint.version(),
                startedCheckpoint.checkpoint().lastEventSequence(),
                authoritativeSpec);
    }

    private static void validatePostReserveContext(
            PlanId planId,
            ContextObservation context,
            PersistedPlanExecutionContextReserved expected) {
        boolean inconsistent =
                context.state() == ContextState.RESERVED
                                && !context.reserved()
                                        .materializationSpec()
                                        .equals(expected.materializationSpec())
                        || context.state() == ContextState.CONFIRMED
                                && !context.confirmed()
                                        .materializationSpec()
                                        .equals(expected.materializationSpec());
        if (inconsistent) {
            throw protocol(
                    planId,
                    PlanExecutionContextCompositionStage
                            .POST_RESERVE_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postReserveContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null);
        }
    }

    private static void validateSuccessfulReserveReconciliation(
            PlanId planId,
            ContextObservation context,
            PersistedPlanExecutionContextReserved expected,
            PersistenceMutationObservation<
                    PersistedPlanExecutionContextReserved> reserve) {
        if (reserve.state() != PersistenceMutationState.APPLIED
                && reserve.state() != PersistenceMutationState.REPLAYED) {
            return;
        }
        PersistedPlanExecutionContextReserved observed =
                context.state() == ContextState.RESERVED
                        ? context.reserved()
                        : context.confirmed().reservation();
        if (!observed.equals(expected)) {
            throw inconsistentPostReserveReconciliation(planId);
        }
    }

    private static PlanExecutionContextCompositionOutcome
            reconcileAbsentPostReserveContext(
                    PlanId planId,
                    PersistenceMutationObservation<
                            PersistedPlanExecutionContextReserved> reserve) {
        return switch (reserve.state()) {
            case REJECTED ->
                    new PlanExecutionContextPersistenceRejected(
                            planId,
                            PlanExecutionContextCompositionStage.RESERVE,
                            reserve.failure(),
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            case NULL_RESULT, THROWN ->
                    new PlanExecutionContextRetryRequired(
                            planId,
                            PlanExecutionContextCompositionStage.RESERVE,
                            PlanExecutionContextRetryReason
                                    .RESERVATION_INDETERMINATE,
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            case APPLIED, REPLAYED ->
                    throw inconsistentPostReserveReconciliation(planId);
        };
    }

    private static PlanExecutionContextCompositionProtocolException
            inconsistentPostReserveReconciliation(PlanId planId) {
        return protocol(
                planId,
                PlanExecutionContextCompositionStage
                        .POST_RESERVE_CONTEXT_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postReserveContextInspectResult.value",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                null);
    }

    PlanExecutionContextCompositionOutcome
            continueAfterReservedOrConfirmedAuthority(
                    ContextObservation context,
                    LeaseRecord lease) {
        if (context.state() == ContextState.CONFIRMED) {
            return observeConfirmedWorkspace(
                    context.confirmed(),
                    PlanExecutionContextCompositionResolution
                            .OBSERVED_CONCURRENT_CONFIRMATION,
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }
        if (context.state() != ContextState.RESERVED) {
            throw new IllegalStateException(
                    "only reserved or confirmed authority may continue");
        }
        PersistedPlanExecutionContextReserved reserved =
                context.reserved();
        Captured workspaceCapture = capture(() ->
                workspacePort.inspectMaterialization(
                        reserved.materializationSpec()));
        WorkspaceObservation workspace = classifyWorkspaceInspection(
                reserved.planId(),
                reserved.materializationSpec(),
                workspaceCapture,
                PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                "planExecutionContextComposition.workspaceInspectResult",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY);
        if (workspace.state() == WorkspaceState.VERIFIED) {
            return confirmAndReinspect(
                    reserved,
                    lease,
                    workspace.verified());
        }
        if (workspace.errorCode()
                != WorkspaceErrorCode.WORKSPACE_NOT_FOUND) {
            return new PlanExecutionContextWorkspaceRejected(
                    reserved.planId(),
                    PlanExecutionContextCompositionStage.WORKSPACE_INSPECT,
                    workspace.errorCode(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }
        return materializeAndReinspect(reserved, lease);
    }

    private PlanExecutionContextCompositionOutcome materializeAndReinspect(
            PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease) {
        Captured materializeCapture = capture(() ->
                workspacePort.materialize(
                        reserved.materializationSpec()));
        Captured postInspectCapture = capture(() ->
                workspacePort.inspectMaterialization(
                        reserved.materializationSpec()));

        WorkspaceObservation postInspect = classifyWorkspaceInspection(
                reserved.planId(),
                reserved.materializationSpec(),
                postInspectCapture,
                PlanExecutionContextCompositionStage
                        .POST_MATERIALIZE_WORKSPACE_INSPECT,
                "planExecutionContextComposition"
                        + ".postMaterializeWorkspaceInspectResult",
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY);
        if (postInspect.state() == WorkspaceState.REJECTED
                && postInspect.errorCode()
                        != WorkspaceErrorCode.WORKSPACE_NOT_FOUND) {
            return new PlanExecutionContextWorkspaceRejected(
                    reserved.planId(),
                    PlanExecutionContextCompositionStage
                            .POST_MATERIALIZE_WORKSPACE_INSPECT,
                    postInspect.errorCode(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }

        WorkspaceObservation materialize =
                classifyWorkspaceMaterialization(
                        reserved.planId(),
                        reserved.materializationSpec(),
                        materializeCapture);
        if (postInspect.state() == WorkspaceState.REJECTED) {
            return reconcileMissingPostMaterialization(
                    reserved.planId(),
                    materialize);
        }
        if (materialize.state() == WorkspaceState.VERIFIED
                && !materialize.verified().equals(
                        postInspect.verified())) {
            throw inconsistentPostMaterializeReconciliation(
                    reserved.planId());
        }
        return confirmAndReinspect(
                reserved,
                lease,
                postInspect.verified());
    }

    private PlanExecutionContextCompositionOutcome confirmAndReinspect(
            PersistedPlanExecutionContextReserved reserved,
            LeaseRecord lease,
            VerifiedWorkspaceMaterialization verifiedWorkspace) {
        PlanExecutionContextConfirmationRequest request =
                new PlanExecutionContextConfirmationRequest(
                        reserved.planId(),
                        lease.leaseToken(),
                        lease.fencingToken(),
                        reserved.materializationSpec(),
                        verifiedWorkspace.sourceManifestFingerprint());
        PersistedPlanExecutionContextConfirmed expected =
                new PersistedPlanExecutionContextConfirmed(
                        reserved,
                        lease.ownerId(),
                        lease.fencingToken(),
                        verifiedWorkspace.sourceManifestFingerprint());
        Captured confirmCapture = capture(() ->
                planExecutionContextRepository.confirm(request));
        Captured postConfirmContextCapture = capture(() ->
                planExecutionContextRepository.inspect(
                        reserved.planId()));

        ContextObservation postConfirmContext =
                classifyContextInspection(
                        reserved.planId(),
                        postConfirmContextCapture,
                        PlanExecutionContextCompositionStage
                                .POST_CONFIRM_CONTEXT_INSPECT,
                        "planExecutionContextComposition"
                                + ".postConfirmContextInspectResult",
                        PlanExecutionContextLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        validatePostConfirmContext(
                reserved,
                postConfirmContext);

        PersistenceMutationObservation<
                PersistedPlanExecutionContextConfirmed> confirm =
                classifyConfirmResult(
                        reserved.planId(),
                        expected,
                        confirmCapture);
        if (postConfirmContext.state() == ContextState.PARTIAL) {
            return new PlanExecutionContextPersistenceRejected(
                    reserved.planId(),
                    PlanExecutionContextCompositionStage
                            .POST_CONFIRM_CONTEXT_INSPECT,
                    postConfirmContext.failure(),
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }
        validatePostConfirmFingerprint(
                reserved,
                verifiedWorkspace,
                postConfirmContext);
        if (postConfirmContext.state() == ContextState.RESERVED) {
            return reconcileReservedPostConfirmContext(
                    reserved.planId(),
                    confirm);
        }
        PersistedPlanExecutionContextConfirmed authoritative =
                postConfirmContext.confirmed();
        if ((confirm.state() == PersistenceMutationState.APPLIED
                        || confirm.state()
                                == PersistenceMutationState.REPLAYED)
                && !authoritative.equals(expected)) {
            throw inconsistentPostConfirmReconciliation(
                    reserved.planId());
        }
        return new PlanExecutionContextReady(
                confirmResolution(confirm),
                authoritative,
                verifiedWorkspace,
                PlanExecutionContextLeaseDisposition
                        .RETAINED_FOR_RECOVERY);
    }

    private static void validatePostConfirmContext(
            PersistedPlanExecutionContextReserved reserved,
            ContextObservation context) {
        if (context.state() == ContextState.NONE
                || context.state() == ContextState.RESERVED
                        && !context.reserved().equals(reserved)
                || context.state() == ContextState.CONFIRMED
                        && !context.confirmed().reservation()
                                .equals(reserved)) {
            throw protocol(
                    reserved.planId(),
                    PlanExecutionContextCompositionStage
                            .POST_CONFIRM_CONTEXT_INSPECT,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    "planExecutionContextComposition"
                            + ".postConfirmContextInspectResult.value",
                    PlanExecutionContextLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null);
        }
    }

    private static void validatePostConfirmFingerprint(
            PersistedPlanExecutionContextReserved reserved,
            VerifiedWorkspaceMaterialization verifiedWorkspace,
            ContextObservation context) {
        if (context.state() == ContextState.CONFIRMED
                && !context.confirmed().sourceManifestFingerprint()
                        .equals(verifiedWorkspace
                                .sourceManifestFingerprint())) {
            throw inconsistentPostConfirmReconciliation(
                    reserved.planId());
        }
    }

    private static PlanExecutionContextCompositionOutcome
            reconcileReservedPostConfirmContext(
                    PlanId planId,
                    PersistenceMutationObservation<
                            PersistedPlanExecutionContextConfirmed> confirm) {
        return switch (confirm.state()) {
            case APPLIED, REPLAYED ->
                    throw inconsistentPostConfirmReconciliation(planId);
            case REJECTED ->
                    new PlanExecutionContextPersistenceRejected(
                            planId,
                            PlanExecutionContextCompositionStage.CONFIRM,
                            confirm.failure(),
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            case NULL_RESULT, THROWN ->
                    new PlanExecutionContextRetryRequired(
                            planId,
                            PlanExecutionContextCompositionStage.CONFIRM,
                            PlanExecutionContextRetryReason
                                    .CONFIRMATION_INDETERMINATE,
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
        };
    }

    private static PlanExecutionContextCompositionResolution
            confirmResolution(
                    PersistenceMutationObservation<
                            PersistedPlanExecutionContextConfirmed> confirm) {
        return switch (confirm.state()) {
            case APPLIED ->
                    PlanExecutionContextCompositionResolution
                            .CONFIRM_APPLIED;
            case REPLAYED ->
                    PlanExecutionContextCompositionResolution
                            .CONFIRM_REPLAYED;
            case REJECTED ->
                    PlanExecutionContextCompositionResolution
                            .OBSERVED_CONCURRENT_CONFIRMATION;
            case NULL_RESULT, THROWN ->
                    PlanExecutionContextCompositionResolution
                            .RECONCILED_AFTER_RESPONSE_LOSS;
        };
    }

    private static PlanExecutionContextCompositionProtocolException
            inconsistentPostConfirmReconciliation(PlanId planId) {
        return protocol(
                planId,
                PlanExecutionContextCompositionStage
                        .POST_CONFIRM_CONTEXT_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postConfirmContextInspectResult.value",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                null);
    }

    private static PlanExecutionContextCompositionOutcome
            reconcileMissingPostMaterialization(
                    PlanId planId,
                    WorkspaceObservation materialize) {
        return switch (materialize.state()) {
            case VERIFIED ->
                    throw inconsistentPostMaterializeReconciliation(
                            planId);
            case REJECTED ->
                    new PlanExecutionContextWorkspaceRejected(
                            planId,
                            PlanExecutionContextCompositionStage
                                    .WORKSPACE_MATERIALIZE,
                            materialize.errorCode(),
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            case NULL_RESULT, THROWN ->
                    new PlanExecutionContextRetryRequired(
                            planId,
                            PlanExecutionContextCompositionStage
                                    .WORKSPACE_MATERIALIZE,
                            PlanExecutionContextRetryReason
                                    .MATERIALIZATION_INDETERMINATE,
                            PlanExecutionContextLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
        };
    }

    private static PlanExecutionContextCompositionProtocolException
            inconsistentPostMaterializeReconciliation(PlanId planId) {
        return protocol(
                planId,
                PlanExecutionContextCompositionStage
                        .POST_MATERIALIZE_WORKSPACE_INSPECT,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                "planExecutionContextComposition"
                        + ".postMaterializeWorkspaceInspectResult.value",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                null);
    }

    static Captured capture(Supplier<?> invocation) {
        try {
            return new Captured(invocation.get(), null);
        } catch (RuntimeException exception) {
            return new Captured(null, exception);
        }
    }

    static ExecutionStartObservation classifyExecutionInspection(
            PlanId planId,
            Captured captured,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        if (captured.exception() != null) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    resultPath,
                    leaseDisposition,
                    captured.exception());
        }
        if (captured.result() == null) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .NULL_COLLABORATOR_RESULT,
                    resultPath,
                    leaseDisposition,
                    null);
        }
        if (!(captured.result() instanceof PersistenceResult<?> result)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        return switch (result.outcome()) {
            case APPLIED, REPLAYED -> throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    resultPath + ".outcome",
                    leaseDisposition,
                    null);
            case FOUND -> executionFound(
                    planId,
                    result,
                    stage,
                    resultPath,
                    leaseDisposition);
            case REJECTED -> executionRejected(
                    planId,
                    result,
                    stage,
                    resultPath,
                    leaseDisposition);
        };
    }

    static ContextObservation classifyContextInspection(
            PlanId planId,
            Captured captured,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        if (captured.exception() != null) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    resultPath,
                    leaseDisposition,
                    captured.exception());
        }
        if (captured.result() == null) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .NULL_COLLABORATOR_RESULT,
                    resultPath,
                    leaseDisposition,
                    null);
        }
        if (!(captured.result() instanceof PersistenceResult<?> result)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        return switch (result.outcome()) {
            case APPLIED, REPLAYED -> throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    resultPath + ".outcome",
                    leaseDisposition,
                    null);
            case FOUND -> contextFound(
                    planId,
                    result,
                    stage,
                    resultPath,
                    leaseDisposition);
            case REJECTED -> contextRejected(
                    planId,
                    result,
                    stage,
                    resultPath,
                    leaseDisposition);
        };
    }

    static PersistenceMutationObservation<LeaseRecord> classifyLeaseResult(
            PlanId planId,
            PlanExecutionContextLeaseAttempt attempt,
            Captured captured,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        return classifyMutation(
                planId,
                captured,
                PlanExecutionContextCompositionStage.LEASE_ACQUIRE,
                "planExecutionContextComposition.leaseAcquireResult",
                leaseDisposition,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_LEASE_AUTHORITY,
                LeaseRecord.class,
                value -> value.planId().equals(planId)
                        && value.ownerId().equals(attempt.leaseOwnerId())
                        && value.leaseToken().equals(attempt.leaseToken())
                        && value.expiresAt().equals(
                                attempt.leaseExpiresAt()),
                DefaultPlanExecutionContextComposer
                        ::isLeaseAcquireFailure);
    }

    static PersistenceMutationObservation<
            PersistedPlanExecutionContextReserved> classifyReserveResult(
                    PlanId planId,
                    PersistedPlanExecutionContextReserved expected,
                    Captured captured) {
        return classifyMutation(
                planId,
                captured,
                PlanExecutionContextCompositionStage.RESERVE,
                "planExecutionContextComposition.reserveResult",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                PersistedPlanExecutionContextReserved.class,
                expected::equals,
                DefaultPlanExecutionContextComposer::isReserveFailure);
    }

    static PersistenceMutationObservation<
            PersistedPlanExecutionContextConfirmed> classifyConfirmResult(
                    PlanId planId,
                    PersistedPlanExecutionContextConfirmed expected,
                    Captured captured) {
        return classifyMutation(
                planId,
                captured,
                PlanExecutionContextCompositionStage.CONFIRM,
                "planExecutionContextComposition.confirmResult",
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_RECONCILIATION_AUTHORITY,
                PersistedPlanExecutionContextConfirmed.class,
                expected::equals,
                DefaultPlanExecutionContextComposer::isConfirmFailure);
    }

    static WorkspaceObservation classifyWorkspaceInspection(
            PlanId planId,
            WorkspaceMaterializationSpec expectedSpec,
            Captured captured,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        if (captured.exception() == null) {
            if (captured.result() == null) {
                throw protocol(
                        planId,
                        stage,
                        PlanExecutionContextCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        resultPath,
                        leaseDisposition,
                        null);
            }
            if (!(captured.result()
                    instanceof VerifiedWorkspaceMaterialization verified)) {
                throw protocol(
                        planId,
                        stage,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_WORKSPACE_AUTHORITY,
                        resultPath + ".value",
                        leaseDisposition,
                        null);
            }
            if (!verified.spec().equals(expectedSpec)) {
                throw protocol(
                        planId,
                        stage,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_WORKSPACE_AUTHORITY,
                        resultPath + ".value",
                        leaseDisposition,
                        null);
            }
            return WorkspaceObservation.verified(verified);
        }
        RuntimeException exception = captured.exception();
        if (!(exception instanceof WorkspaceException workspaceException)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    resultPath,
                    leaseDisposition,
                    exception);
        }
        if (!isCanonicalInspectionFailure(workspaceException)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_WORKSPACE_AUTHORITY,
                    resultPath + ".failure",
                    leaseDisposition,
                    null);
        }
        return WorkspaceObservation.rejected(workspaceException.code());
    }

    static WorkspaceObservation classifyWorkspaceMaterialization(
            PlanId planId,
            WorkspaceMaterializationSpec expectedSpec,
            Captured captured) {
        String resultPath =
                "planExecutionContextComposition.workspaceMaterializeResult";
        PlanExecutionContextCompositionStage stage =
                PlanExecutionContextCompositionStage.WORKSPACE_MATERIALIZE;
        PlanExecutionContextLeaseDisposition leaseDisposition =
                PlanExecutionContextLeaseDisposition.RETAINED_FOR_RECOVERY;
        if (captured.exception() == null) {
            if (captured.result() == null) {
                return WorkspaceObservation.nullResult();
            }
            if (!(captured.result()
                    instanceof VerifiedWorkspaceMaterialization verified)
                    || !verified.spec().equals(expectedSpec)) {
                throw protocol(
                        planId,
                        stage,
                        PlanExecutionContextCompositionProtocolCode
                                .INCONSISTENT_WORKSPACE_AUTHORITY,
                        resultPath + ".value",
                        leaseDisposition,
                        null);
            }
            return WorkspaceObservation.verified(verified);
        }
        RuntimeException exception = captured.exception();
        if (!(exception instanceof WorkspaceException workspaceException)) {
            return WorkspaceObservation.thrown(exception);
        }
        if (!isCanonicalMaterializationFailure(workspaceException)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_WORKSPACE_AUTHORITY,
                    resultPath + ".failure",
                    leaseDisposition,
                    null);
        }
        return WorkspaceObservation.rejected(workspaceException.code());
    }

    private static ExecutionStartObservation executionFound(
            PlanId planId,
            PersistenceResult<?> result,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        Object value = result.value().orElse(null);
        if (!(value instanceof ExecutionStartRecoverySnapshot snapshot)
                || !snapshot.planId().equals(planId)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        if (snapshot instanceof PersistedExecutionStartReady ready) {
            return ExecutionStartObservation.ready(ready);
        }
        if (snapshot instanceof PersistedExecutionStartCommitted committed) {
            return ExecutionStartObservation.committed(committed);
        }
        throw protocol(
                planId,
                stage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_EXECUTION_START_AUTHORITY,
                resultPath + ".value",
                leaseDisposition,
                null);
    }

    private static ExecutionStartObservation executionRejected(
            PlanId planId,
            PersistenceResult<?> result,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        Object value = result.failure().orElse(null);
        if (!(value instanceof PersistenceFailure failure)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_EXECUTION_START_AUTHORITY,
                    resultPath + ".failure",
                    leaseDisposition,
                    null);
        }
        if (matches(failure, PersistenceErrorCode.NOT_FOUND, "planId")) {
            return ExecutionStartObservation.notFound(failure);
        }
        if (matches(
                failure,
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery")) {
            return ExecutionStartObservation.partial(failure);
        }
        if (matches(
                failure,
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery")) {
            return ExecutionStartObservation.advanced(failure);
        }
        throw protocol(
                planId,
                stage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_EXECUTION_START_AUTHORITY,
                resultPath + ".failure",
                leaseDisposition,
                null);
    }

    private static ContextObservation contextFound(
            PlanId planId,
            PersistenceResult<?> result,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        Object value = result.value().orElse(null);
        if (!(value instanceof PlanExecutionContextSnapshot snapshot)
                || !snapshot.planId().equals(planId)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        if (snapshot instanceof PersistedPlanExecutionContextReserved
                reserved) {
            return ContextObservation.reserved(reserved);
        }
        if (snapshot instanceof PersistedPlanExecutionContextConfirmed
                confirmed) {
            return ContextObservation.confirmed(confirmed);
        }
        throw protocol(
                planId,
                stage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                resultPath + ".value",
                leaseDisposition,
                null);
    }

    private static ContextObservation contextRejected(
            PlanId planId,
            PersistenceResult<?> result,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition) {
        Object value = result.failure().orElse(null);
        if (!(value instanceof PersistenceFailure failure)) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .INCONSISTENT_CONTEXT_AUTHORITY,
                    resultPath + ".failure",
                    leaseDisposition,
                    null);
        }
        if (matches(
                failure,
                PersistenceErrorCode.NOT_FOUND,
                "planExecutionContext")) {
            return ContextObservation.none();
        }
        if (matches(
                failure,
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext")) {
            return ContextObservation.partial(failure);
        }
        throw protocol(
                planId,
                stage,
                PlanExecutionContextCompositionProtocolCode
                        .INCONSISTENT_CONTEXT_AUTHORITY,
                resultPath + ".failure",
                leaseDisposition,
                null);
    }

    private static <T> PersistenceMutationObservation<T> classifyMutation(
            PlanId planId,
            Captured captured,
            PlanExecutionContextCompositionStage stage,
            String resultPath,
            PlanExecutionContextLeaseDisposition leaseDisposition,
            PlanExecutionContextCompositionProtocolCode authorityCode,
            Class<T> valueType,
            Predicate<T> valueAuthority,
            Predicate<PersistenceFailure> failureAuthority) {
        if (captured.exception() != null) {
            return PersistenceMutationObservation.thrown(
                    captured.exception());
        }
        if (captured.result() == null) {
            return PersistenceMutationObservation.nullResult();
        }
        if (!(captured.result() instanceof PersistenceResult<?> result)) {
            throw protocol(
                    planId,
                    stage,
                    authorityCode,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        if (result.outcome() == PersistenceOutcome.FOUND) {
            throw protocol(
                    planId,
                    stage,
                    PlanExecutionContextCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    resultPath + ".outcome",
                    leaseDisposition,
                    null);
        }
        if (result.outcome() == PersistenceOutcome.REJECTED) {
            Object value = result.failure().orElse(null);
            if (!(value instanceof PersistenceFailure failure)
                    || !failureAuthority.test(failure)) {
                throw protocol(
                        planId,
                        stage,
                        authorityCode,
                        resultPath + ".failure",
                        leaseDisposition,
                        null);
            }
            return PersistenceMutationObservation.rejected(failure);
        }
        Object value = result.value().orElse(null);
        if (!valueType.isInstance(value)) {
            throw protocol(
                    planId,
                    stage,
                    authorityCode,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        T typed = valueType.cast(value);
        if (!valueAuthority.test(typed)) {
            throw protocol(
                    planId,
                    stage,
                    authorityCode,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        return result.outcome() == PersistenceOutcome.APPLIED
                ? PersistenceMutationObservation.applied(typed)
                : PersistenceMutationObservation.replayed(typed);
    }

    private static boolean isCanonicalInspectionFailure(
            WorkspaceException exception) {
        return exception.operation().equals("inspectMaterialization")
                && exception.projectPath().isEmpty()
                && switch (exception.code()) {
                    case WORKSPACE_NOT_FOUND, WORKSPACE_SPEC_CONFLICT,
                            WORKSPACE_RETIRED, WORKSPACE_PARTIAL_STATE,
                            PATH_ESCAPE, LINK_ESCAPE -> true;
                    default -> false;
                };
    }

    private static boolean isCanonicalMaterializationFailure(
            WorkspaceException exception) {
        return exception.operation().equals("materialize")
                && switch (exception.code()) {
                    case INVALID_METADATA, SOURCE_FAILURE,
                            SOURCE_REFERENCE_MISMATCH, DUPLICATE_PATH,
                            PATH_COLLISION, HASH_MISMATCH,
                            FILE_LIMIT_EXCEEDED, AGGREGATE_LIMIT_EXCEEDED,
                            FILE_COUNT_LIMIT_EXCEEDED,
                            WORKSPACE_SPEC_CONFLICT, WORKSPACE_RETIRED,
                            WORKSPACE_PARTIAL_STATE, PATH_ESCAPE, LINK_ESCAPE,
                            NOT_REGULAR_FILE, TEMPORARY_PATH_OCCUPIED,
                            SOURCE_MANIFEST_FINGERPRINT_MISMATCH,
                            MATERIALIZATION_VERIFICATION_FAILED,
                            ATOMIC_PUBLISH_NOT_SUPPORTED, IO_FAILURE -> true;
                    default -> false;
                };
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
        return matches(
                        failure,
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                        "planExecutionContext")
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
                        "request.materializationSpec.sourceProjectVersion")
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
                || matches(
                        failure,
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionId")
                || matches(
                        failure,
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionNumber")
                || matches(
                        failure,
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedCheckpointVersion")
                || matches(
                        failure,
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedEventHeadSequence");
    }

    private static boolean isConfirmFailure(PersistenceFailure failure) {
        return matches(
                        failure,
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                        "planExecutionContext")
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
                        "planExecutionContext")
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

    private static boolean matches(
            PersistenceFailure failure,
            PersistenceErrorCode code,
            String path) {
        return failure.code() == code && failure.path().equals(path);
    }

    private static PlanExecutionContextCompositionProtocolException protocol(
            PlanId planId,
            PlanExecutionContextCompositionStage stage,
            PlanExecutionContextCompositionProtocolCode code,
            String path,
            PlanExecutionContextLeaseDisposition leaseDisposition,
            Throwable cause) {
        return PlanExecutionContextCompositionValues.protocolFailure(
                planId,
                stage,
                code,
                path,
                leaseDisposition,
                cause);
    }

    record Captured(Object result, RuntimeException exception) {
    }

    enum ExecutionStartState {
        READY,
        COMMITTED,
        ADVANCED,
        PARTIAL,
        NOT_FOUND
    }

    record ExecutionStartObservation(
            ExecutionStartState state,
            PersistedExecutionStartReady ready,
            PersistedExecutionStartCommitted committed,
            PersistenceFailure failure) {
        static ExecutionStartObservation ready(
                PersistedExecutionStartReady value) {
            return new ExecutionStartObservation(
                    ExecutionStartState.READY,
                    value,
                    null,
                    null);
        }

        static ExecutionStartObservation committed(
                PersistedExecutionStartCommitted value) {
            return new ExecutionStartObservation(
                    ExecutionStartState.COMMITTED,
                    null,
                    value,
                    null);
        }

        static ExecutionStartObservation advanced(
                PersistenceFailure failure) {
            return new ExecutionStartObservation(
                    ExecutionStartState.ADVANCED,
                    null,
                    null,
                    failure);
        }

        static ExecutionStartObservation partial(
                PersistenceFailure failure) {
            return new ExecutionStartObservation(
                    ExecutionStartState.PARTIAL,
                    null,
                    null,
                    failure);
        }

        static ExecutionStartObservation notFound(
                PersistenceFailure failure) {
            return new ExecutionStartObservation(
                    ExecutionStartState.NOT_FOUND,
                    null,
                    null,
                    failure);
        }
    }

    enum ContextState {
        NONE,
        RESERVED,
        CONFIRMED,
        PARTIAL
    }

    record ContextObservation(
            ContextState state,
            PersistedPlanExecutionContextReserved reserved,
            PersistedPlanExecutionContextConfirmed confirmed,
            PersistenceFailure failure) {
        static ContextObservation none() {
            return new ContextObservation(
                    ContextState.NONE,
                    null,
                    null,
                    null);
        }

        static ContextObservation reserved(
                PersistedPlanExecutionContextReserved value) {
            return new ContextObservation(
                    ContextState.RESERVED,
                    value,
                    null,
                    null);
        }

        static ContextObservation confirmed(
                PersistedPlanExecutionContextConfirmed value) {
            return new ContextObservation(
                    ContextState.CONFIRMED,
                    null,
                    value,
                    null);
        }

        static ContextObservation partial(
                PersistenceFailure failure) {
            return new ContextObservation(
                    ContextState.PARTIAL,
                    null,
                    null,
                    failure);
        }
    }

    enum PersistenceMutationState {
        APPLIED,
        REPLAYED,
        REJECTED,
        NULL_RESULT,
        THROWN
    }

    record PersistenceMutationObservation<T>(
            PersistenceMutationState state,
            T value,
            PersistenceFailure failure,
            RuntimeException exception) {
        static <T> PersistenceMutationObservation<T> applied(T value) {
            return new PersistenceMutationObservation<>(
                    PersistenceMutationState.APPLIED,
                    value,
                    null,
                    null);
        }

        static <T> PersistenceMutationObservation<T> replayed(T value) {
            return new PersistenceMutationObservation<>(
                    PersistenceMutationState.REPLAYED,
                    value,
                    null,
                    null);
        }

        static <T> PersistenceMutationObservation<T> rejected(
                PersistenceFailure failure) {
            return new PersistenceMutationObservation<>(
                    PersistenceMutationState.REJECTED,
                    null,
                    failure,
                    null);
        }

        static <T> PersistenceMutationObservation<T> nullResult() {
            return new PersistenceMutationObservation<>(
                    PersistenceMutationState.NULL_RESULT,
                    null,
                    null,
                    null);
        }

        static <T> PersistenceMutationObservation<T> thrown(
                RuntimeException exception) {
            return new PersistenceMutationObservation<>(
                    PersistenceMutationState.THROWN,
                    null,
                    null,
                    exception);
        }
    }

    enum WorkspaceState {
        VERIFIED,
        REJECTED,
        NULL_RESULT,
        THROWN
    }

    record WorkspaceObservation(
            WorkspaceState state,
            VerifiedWorkspaceMaterialization verified,
            WorkspaceErrorCode errorCode,
            RuntimeException exception) {
        static WorkspaceObservation verified(
                VerifiedWorkspaceMaterialization value) {
            return new WorkspaceObservation(
                    WorkspaceState.VERIFIED,
                    value,
                    null,
                    null);
        }

        static WorkspaceObservation rejected(WorkspaceErrorCode code) {
            return new WorkspaceObservation(
                    WorkspaceState.REJECTED,
                    null,
                    code,
                    null);
        }

        static WorkspaceObservation nullResult() {
            return new WorkspaceObservation(
                    WorkspaceState.NULL_RESULT,
                    null,
                    null,
                    null);
        }

        static WorkspaceObservation thrown(RuntimeException exception) {
            return new WorkspaceObservation(
                    WorkspaceState.THROWN,
                    null,
                    null,
                    exception);
        }
    }
}
