package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

import java.time.Instant;

final class InMemoryPlanExecutionContextRepository
        implements PlanExecutionContextRepository {
    private static final String CONTEXT_PATH = "planExecutionContext";
    private static final String SOURCE_PATH = "planExecutionContext.source";

    private final InMemoryState state;

    InMemoryPlanExecutionContextRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedPlanExecutionContextReserved> reserve(
            PlanExecutionContextReservationRequest request) {
        if (request == null) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            boolean contextOccupancy = hasContextOccupancy(
                    request.planId());
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    contextOccupancy
                            ? InMemoryExecutionMutationAuthority
                                    .validateAuthoritativeSource(
                                            state, request.planId())
                            : null;
            InMemoryPlanExecutionContextAuthority.ContextCut cut =
                    InMemoryPlanExecutionContextAuthority.inspect(
                            state,
                            request.planId(),
                            contextOccupancy ? source : null);
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.PARTIAL) {
                return partialState();
            }
            if (contextOccupancy) {
                InMemoryState.PlanExecutionContextReservationMarker marker =
                        state.planExecutionContextReservations.get(
                                request.planId());
                if (marker == null) {
                    return partialState();
                }
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId");
            }

            Instant effectiveNow = state.observeLeaseTime();
            InMemoryExecutionMutationAuthority.PlanRoot root =
                    InMemoryExecutionMutationAuthority.validatePlanRoot(
                            state, request.planId());
            if (root == null) {
                return InMemoryExecutionMutationAuthority
                                .hasPlanScopedOccupancy(
                                        state, request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            source = InMemoryExecutionMutationAuthority
                    .validateAuthoritativeSource(
                            state, request.planId());
            contextOccupancy = hasContextOccupancy(
                    request.planId());
            cut = InMemoryPlanExecutionContextAuthority.inspect(
                    state,
                    request.planId(),
                    contextOccupancy ? source : null);
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.PARTIAL) {
                return partialState();
            }
            if (contextOccupancy) {
                InMemoryState.PlanExecutionContextReservationMarker marker =
                        state.planExecutionContextReservations.get(
                                request.planId());
                if (marker == null) {
                    return partialState();
                }
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId");
            }
            InMemoryPlanExecutionContextAuthority.ExecutionStatus
                    executionStatus =
                    InMemoryPlanExecutionContextAuthority
                            .classifyExecution(
                                    state, request.planId(), source);
            if (executionStatus
                    == InMemoryPlanExecutionContextAuthority
                            .ExecutionStatus.STRICT_PRE_START) {
                return notEligible();
            }
            if (executionStatus
                    == InMemoryPlanExecutionContextAuthority
                            .ExecutionStatus.PARTIAL) {
                return partialState();
            }
            if (!source.links().isEmpty()) {
                return notEligible();
            }

            PersistenceResult<PersistedPlanExecutionContextReserved>
                    invalidLease = validateLease(
                            request.planId(),
                            request.leaseToken(),
                            request.fencingToken(),
                            effectiveNow);
            if (invalidLease != null) {
                return invalidLease;
            }
            if (!source.head().revisionId().equals(
                    request.expectedRevisionId())) {
                return stale("request.expectedRevisionId");
            }
            if (source.head().revisionNumber()
                    != request.expectedRevisionNumber()) {
                return stale("request.expectedRevisionNumber");
            }
            if (source.head().checkpointVersion()
                    != request.expectedCheckpointVersion()) {
                return stale("request.expectedCheckpointVersion");
            }
            if (source.head().eventHeadSequence()
                    != request.expectedEventHeadSequence()) {
                return stale("request.expectedEventHeadSequence");
            }
            if (source.head().checkpointVersion() != 2
                    || source.head().eventHeadSequence() != 1
                    || root.taskFrame()
                            .sourceProjectVersion()
                            .isEmpty()) {
                return notEligible();
            }
            if (!root.taskFrame()
                    .sourceProjectVersion()
                    .orElseThrow()
                    .equals(request.materializationSpec()
                            .sourceProjectVersion())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "request.materializationSpec.sourceProjectVersion");
            }

            InMemoryState.WorkspaceOwner existingOwner =
                    InMemoryPlanExecutionContextAuthority.ownerForWorkspace(
                            state,
                            request.materializationSpec().workspaceId());
            if (existingOwner != null) {
                InMemoryExecutionMutationAuthority.AuthoritativeSource
                        ownerSource =
                        InMemoryExecutionMutationAuthority
                                .validateAuthoritativeSource(
                                        state, existingOwner.planId());
                InMemoryPlanExecutionContextAuthority.ContextCut ownerCut =
                        InMemoryPlanExecutionContextAuthority.inspect(
                                state,
                                existingOwner.planId(),
                                ownerSource);
                if (ownerCut.status()
                                == InMemoryPlanExecutionContextAuthority
                                        .Status.RESERVED
                        || ownerCut.status()
                                == InMemoryPlanExecutionContextAuthority
                                        .Status.CONFIRMED) {
                    return PersistenceResult.rejected(
                            PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.materializationSpec.workspaceId");
                }
                return partialState();
            }

            LeaseRecord lease = state.leases.get(request.planId());
            PersistedPlanExecutionContextReserved result =
                    new PersistedPlanExecutionContextReserved(
                            request.planId(),
                            request.materializationSpec(),
                            lease.ownerId(),
                            lease.fencingToken());
            InMemoryState.PlanExecutionContextReservationMarker marker =
                    new InMemoryState
                            .PlanExecutionContextReservationMarker(
                                    request, result, source.head());
            InMemoryState.WorkspaceOwner owner =
                    new InMemoryState.WorkspaceOwner(
                            request.planId(),
                            request.materializationSpec());
            state.planExecutionContextReservations.put(
                    request.planId(), marker);
            state.workspaceOwners.put(
                    request.materializationSpec().workspaceId(), owner);
            return PersistenceResult.applied(result);
        }
    }

    @Override
    public PersistenceResult<PersistedPlanExecutionContextConfirmed> confirm(
            PlanExecutionContextConfirmationRequest request) {
        if (request == null) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority
                            .validateAuthoritativeSource(
                                    state, request.planId());
            boolean contextOccupancy = hasContextOccupancy(
                    request.planId());
            InMemoryPlanExecutionContextAuthority.ContextCut cut =
                    InMemoryPlanExecutionContextAuthority.inspect(
                            state,
                            request.planId(),
                            contextOccupancy ? source : null);
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.PARTIAL) {
                return partialState();
            }
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.CONFIRMED) {
                InMemoryState.PlanExecutionContextConfirmationMarker marker =
                        state.planExecutionContextConfirmations.get(
                                request.planId());
                if (marker == null) {
                    return partialState();
                }
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId");
            }

            Instant effectiveNow = state.observeLeaseTime();
            InMemoryExecutionMutationAuthority.PlanRoot root =
                    InMemoryExecutionMutationAuthority.validatePlanRoot(
                            state, request.planId());
            if (root == null) {
                return InMemoryExecutionMutationAuthority
                                .hasPlanScopedOccupancy(
                                        state, request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            source = InMemoryExecutionMutationAuthority
                    .validateAuthoritativeSource(
                            state, request.planId());
            cut = InMemoryPlanExecutionContextAuthority.inspect(
                    state, request.planId(), source);
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.PARTIAL) {
                return partialState();
            }
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.CONFIRMED) {
                InMemoryState.PlanExecutionContextConfirmationMarker marker =
                        state.planExecutionContextConfirmations.get(
                                request.planId());
                if (marker == null) {
                    return partialState();
                }
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId");
            }
            InMemoryPlanExecutionContextAuthority.ExecutionStatus
                    executionStatus =
                    InMemoryPlanExecutionContextAuthority
                            .classifyExecution(
                                    state, request.planId(), source);
            if (executionStatus
                    == InMemoryPlanExecutionContextAuthority
                            .ExecutionStatus.PARTIAL) {
                return partialState();
            }
            if (root.taskFrame().sourceProjectVersion().isEmpty()) {
                return notEligibleConfirmation();
            }
            if (cut.status()
                    == InMemoryPlanExecutionContextAuthority.Status.NONE) {
                if (source != null && !source.links().isEmpty()) {
                    return partialState();
                }
                return PersistenceChecks.notFound(CONTEXT_PATH);
            }
            if (source == null
                    || !source.links().isEmpty()
                    || cut.reservation() == null) {
                return partialState();
            }

            PersistenceResult<PersistedPlanExecutionContextConfirmed>
                    invalidLease = validateLease(
                            request.planId(),
                            request.leaseToken(),
                            request.fencingToken(),
                            effectiveNow);
            if (invalidLease != null) {
                return invalidLease;
            }
            if (!request.materializationSpec().equals(
                    cut.reservation().materializationSpec())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.materializationSpec");
            }

            LeaseRecord lease = state.leases.get(request.planId());
            PersistedPlanExecutionContextConfirmed result =
                    new PersistedPlanExecutionContextConfirmed(
                            cut.reservation(),
                            lease.ownerId(),
                            lease.fencingToken(),
                            request.sourceManifestFingerprint());
            InMemoryState.PlanExecutionContextConfirmationMarker marker =
                    new InMemoryState
                            .PlanExecutionContextConfirmationMarker(
                                    request, result);
            state.planExecutionContextConfirmations.put(
                    request.planId(), marker);
            return PersistenceResult.applied(result);
        }
    }

    @Override
    public PersistenceResult<PlanExecutionContextSnapshot> inspect(
            PlanId planId) {
        if (planId == null) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            if (!InMemoryExecutionMutationAuthority
                    .hasPlanScopedOccupancy(state, planId)) {
                return PersistenceChecks.notFound("planId");
            }
            InMemoryExecutionMutationAuthority.PlanRoot root =
                    InMemoryExecutionMutationAuthority.validatePlanRoot(
                            state, planId);
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority
                            .validateAuthoritativeSource(state, planId);
            InMemoryPlanExecutionContextAuthority.ExecutionStatus
                    executionStatus =
                    root == null
                            ? InMemoryPlanExecutionContextAuthority
                                    .ExecutionStatus.PARTIAL
                            : InMemoryPlanExecutionContextAuthority
                                    .classifyExecution(
                                            state, planId, source);
            InMemoryPlanExecutionContextAuthority.ContextCut cut =
                    InMemoryPlanExecutionContextAuthority.inspect(
                            state, planId, source);
            if (root == null
                    || executionStatus
                            == InMemoryPlanExecutionContextAuthority
                                    .ExecutionStatus.PARTIAL
                    || cut.status()
                            == InMemoryPlanExecutionContextAuthority
                                    .Status.PARTIAL) {
                return partialInspection();
            }
            return switch (cut.status()) {
                case RESERVED -> PersistenceResult.found(
                        cut.reservation());
                case CONFIRMED -> PersistenceResult.found(
                        cut.confirmation());
                case NONE -> PersistenceChecks.notFound(CONTEXT_PATH);
                case PARTIAL -> partialInspection();
            };
        }
    }

    private boolean hasContextOccupancy(PlanId planId) {
        return state.planExecutionContextReservations.containsKey(planId)
                || state.planExecutionContextConfirmations.containsKey(planId)
                || InMemoryPlanExecutionContextAuthority
                        .hasOwnerReference(state, planId);
    }

    private <T> PersistenceResult<T> validateLease(
            PlanId planId,
            String leaseToken,
            long fencingToken,
            Instant effectiveNow) {
        LeaseRecord lease = state.leases.get(planId);
        if (lease == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.planId");
        }
        if (!lease.leaseToken().equals(leaseToken)) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != fencingToken) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (lease.isExpiredAt(effectiveNow)) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_EXPIRED,
                    "request.planId");
        }
        return null;
    }

    private static <T> PersistenceResult<T> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                CONTEXT_PATH);
    }

    private static PersistenceResult<PlanExecutionContextSnapshot>
            partialInspection() {
        return partialState();
    }

    private static PersistenceResult<PersistedPlanExecutionContextReserved>
            notEligible() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                SOURCE_PATH);
    }

    private static PersistenceResult<PersistedPlanExecutionContextConfirmed>
            notEligibleConfirmation() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                SOURCE_PATH);
    }

    private static PersistenceResult<PersistedPlanExecutionContextReserved>
            stale(String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STALE_VERSION, path);
    }
}
