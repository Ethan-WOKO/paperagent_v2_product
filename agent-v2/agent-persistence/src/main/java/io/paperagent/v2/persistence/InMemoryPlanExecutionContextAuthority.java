package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceId;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class InMemoryPlanExecutionContextAuthority {
    private InMemoryPlanExecutionContextAuthority() {
    }

    static ContextCut inspect(
            InMemoryState state,
            PlanId planId,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        if (!hasValidGlobalIndex(state)) {
            return ContextCut.partial();
        }

        boolean hasReservation =
                state.planExecutionContextReservations.containsKey(planId);
        boolean hasConfirmation =
                state.planExecutionContextConfirmations.containsKey(planId);
        InMemoryState.PlanExecutionContextReservationMarker reservation =
                state.planExecutionContextReservations.get(planId);
        InMemoryState.PlanExecutionContextConfirmationMarker confirmation =
                state.planExecutionContextConfirmations.get(planId);
        OwnerLookup owners = ownersForPlan(state, planId);
        if (owners.partial()
                || hasReservation && reservation == null
                || hasConfirmation && confirmation == null
                || !hasReservation && hasConfirmation
                || !hasReservation && owners.owner() != null
                || hasReservation && owners.owner() == null) {
            return ContextCut.partial();
        }

        InMemoryExecutionMutationAuthority.PlanRoot root =
                InMemoryExecutionMutationAuthority.validatePlanRoot(
                        state, planId);
        if (root == null) {
            return hasReservation || hasConfirmation || owners.owner() != null
                    ? ContextCut.partial()
                    : ContextCut.none();
        }

        if (!hasReservation) {
            if (source != null
                    && root.taskFrame().sourceProjectVersion().isPresent()
                    && !source.links().isEmpty()) {
                return ContextCut.partial();
            }
            return ContextCut.none();
        }
        if (root.taskFrame().sourceProjectVersion().isEmpty()
                || source == null
                || !isReservationConsistent(
                        state,
                        planId,
                        root,
                        reservation,
                        owners.owner())) {
            return ContextCut.partial();
        }

        if (!hasConfirmation) {
            return source.links().isEmpty()
                            && source.head().equals(reservation.frozenH0())
                    ? ContextCut.reserved(reservation.result())
                    : ContextCut.partial();
        }
        if (!isConfirmationConsistent(
                planId, reservation, confirmation)) {
            return ContextCut.partial();
        }
        return ContextCut.confirmed(
                reservation.result(), confirmation.result());
    }

    static boolean hasOwnerReference(
            InMemoryState state,
            PlanId planId) {
        for (InMemoryState.WorkspaceOwner owner :
                state.workspaceOwners.values()) {
            if (owner != null && planId.equals(owner.planId())) {
                return true;
            }
        }
        return false;
    }

    static InMemoryState.WorkspaceOwner ownerForWorkspace(
            InMemoryState state,
            WorkspaceId workspaceId) {
        return state.workspaceOwners.get(workspaceId);
    }

    static ExecutionStatus classifyExecution(
            InMemoryState state,
            PlanId planId,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        if (source != null) {
            return ExecutionStatus.AUTHORITATIVE;
        }
        InMemoryExecutionMutationAuthority.PlanRoot root =
                InMemoryExecutionMutationAuthority.validatePlanRoot(
                        state, planId);
        if (root == null) {
            return ExecutionStatus.PARTIAL;
        }
        return isStrictPreStart(
                        state,
                        planId,
                        root.bootstrap(),
                        root.plan())
                ? ExecutionStatus.STRICT_PRE_START
                : ExecutionStatus.PARTIAL;
    }

    static boolean isStrictPreStart(
            InMemoryState state,
            PlanId planId,
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan) {
        var stream = state.eventStreams.get(planId);
        return bootstrap.initialCheckpoint()
                        .equals(state.checkpoints.get(planId))
                && (stream == null
                        ? !state.eventStreams.containsKey(planId)
                        : stream.isEmpty())
                && state.eventsById.values().stream()
                        .noneMatch(event ->
                                event != null
                                        && planId.equals(event.planId()))
                && currentPlan.latestRevision().completedFacts().isEmpty()
                && !state.executionStarts.containsKey(planId)
                && !state.executionMutationHeads.containsKey(planId)
                && !state.executionMutationLinks.containsKey(planId)
                && !state.stepActivations.containsKey(planId)
                && !state.stepCompletions.containsKey(planId)
                && !state.stepPauses.containsKey(planId)
                && !state.stepFailures.containsKey(planId)
                && !state.stepCancellations.containsKey(planId);
    }

    static boolean hasValidGlobalIndex(InMemoryState state) {
        Set<PlanId> plans = new HashSet<>();
        for (Map.Entry<WorkspaceId, InMemoryState.WorkspaceOwner> entry :
                state.workspaceOwners.entrySet()) {
            WorkspaceId key = entry.getKey();
            InMemoryState.WorkspaceOwner owner = entry.getValue();
            if (key == null
                    || owner == null
                    || owner.planId() == null
                    || owner.materializationSpec() == null
                    || owner.materializationSpec().workspaceId() == null
                    || !key.equals(
                            owner.materializationSpec().workspaceId())
                    || !plans.add(owner.planId())) {
                return false;
            }
            InMemoryState.PlanExecutionContextReservationMarker marker =
                    state.planExecutionContextReservations.get(
                            owner.planId());
            if (!isExactOwnerReservationPair(
                    owner.planId(), owner, marker)) {
                return false;
            }
        }
        for (Map.Entry<PlanId,
                InMemoryState.PlanExecutionContextReservationMarker> entry :
                state.planExecutionContextReservations.entrySet()) {
            PlanId planId = entry.getKey();
            InMemoryState.PlanExecutionContextReservationMarker marker =
                    entry.getValue();
            if (planId == null
                    || marker == null
                    || marker.request() == null
                    || marker.request().materializationSpec() == null) {
                return false;
            }
            InMemoryState.WorkspaceOwner owner =
                    state.workspaceOwners.get(
                            marker.request()
                                    .materializationSpec()
                                    .workspaceId());
            if (!isExactOwnerReservationPair(
                    planId, owner, marker)) {
                return false;
            }
        }
        for (Map.Entry<PlanId,
                InMemoryState.PlanExecutionContextConfirmationMarker> entry :
                state.planExecutionContextConfirmations.entrySet()) {
            PlanId planId = entry.getKey();
            InMemoryState.PlanExecutionContextConfirmationMarker marker =
                    entry.getValue();
            if (planId == null
                    || marker == null
                    || marker.request() == null
                    || marker.result() == null
                    || !isLocalConfirmationPair(
                            planId,
                            state.planExecutionContextReservations
                                    .get(planId),
                            marker)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExactOwnerReservationPair(
            PlanId planId,
            InMemoryState.WorkspaceOwner owner,
            InMemoryState.PlanExecutionContextReservationMarker marker) {
        if (planId == null
                || owner == null
                || marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.frozenH0() == null
                || marker.request().planId() == null
                || marker.result().planId() == null
                || marker.request().materializationSpec() == null
                || marker.result().materializationSpec() == null) {
            return false;
        }
        return planId.equals(owner.planId())
                && planId.equals(marker.request().planId())
                && planId.equals(marker.result().planId())
                && owner.materializationSpec().equals(
                        marker.request().materializationSpec())
                && owner.materializationSpec().equals(
                        marker.result().materializationSpec())
                && marker.request().fencingToken()
                        == marker.result().fencingToken()
                && marker.request().expectedRevisionId().equals(
                        marker.frozenH0().revisionId())
                && marker.request().expectedRevisionNumber()
                        == marker.frozenH0().revisionNumber()
                && marker.request().expectedCheckpointVersion()
                        == marker.frozenH0().checkpointVersion()
                && marker.request().expectedEventHeadSequence()
                        == marker.frozenH0().eventHeadSequence();
    }

    private static boolean isLocalConfirmationPair(
            PlanId planId,
            InMemoryState.PlanExecutionContextReservationMarker reservation,
            InMemoryState.PlanExecutionContextConfirmationMarker confirmation) {
        if (reservation == null
                || reservation.request() == null
                || reservation.result() == null
                || confirmation == null
                || confirmation.request() == null
                || confirmation.result() == null) {
            return false;
        }
        var request = confirmation.request();
        var result = confirmation.result();
        return planId.equals(request.planId())
                && planId.equals(result.planId())
                && request.materializationSpec().equals(
                        reservation.request().materializationSpec())
                && request.materializationSpec().equals(
                        result.materializationSpec())
                && result.reservation().equals(reservation.result())
                && request.fencingToken() == result.fencingToken()
                && request.sourceManifestFingerprint().equals(
                        result.sourceManifestFingerprint());
    }

    private static OwnerLookup ownersForPlan(
            InMemoryState state,
            PlanId planId) {
        InMemoryState.WorkspaceOwner found = null;
        for (InMemoryState.WorkspaceOwner owner :
                state.workspaceOwners.values()) {
            if (owner != null && planId.equals(owner.planId())) {
                if (found != null) {
                    return OwnerLookup.corrupt();
                }
                found = owner;
            }
        }
        return OwnerLookup.valid(found);
    }

    private static boolean isReservationConsistent(
            InMemoryState state,
            PlanId planId,
            InMemoryExecutionMutationAuthority.PlanRoot root,
            InMemoryState.PlanExecutionContextReservationMarker marker,
            InMemoryState.WorkspaceOwner owner) {
        if (marker.request() == null
                || marker.result() == null
                || marker.frozenH0() == null
                || owner == null) {
            return false;
        }
        PlanExecutionContextReservationRequest request =
                marker.request();
        PersistedPlanExecutionContextReserved result =
                marker.result();
        InMemoryState.ExecutionStartMarker start =
                state.executionStarts.get(planId);
        if (start == null
                || start.result() == null
                || root.taskFrame().sourceProjectVersion().isEmpty()) {
            return false;
        }
        var sourceProjectVersion =
                root.taskFrame().sourceProjectVersion().orElseThrow();
        var h0 = InMemoryExecutionMutationAuthority.headFromStart(
                start.result());
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.materializationSpec().equals(
                        result.materializationSpec())
                && request.fencingToken() == result.fencingToken()
                && request.expectedRevisionId().equals(
                        marker.frozenH0().revisionId())
                && request.expectedRevisionNumber()
                        == marker.frozenH0().revisionNumber()
                && request.expectedCheckpointVersion()
                        == marker.frozenH0().checkpointVersion()
                && request.expectedEventHeadSequence()
                        == marker.frozenH0().eventHeadSequence()
                && marker.frozenH0().equals(h0)
                && marker.frozenH0().equals(
                        new InMemoryState.ExecutionMutationHead(
                                request.expectedRevisionId(),
                                request.expectedRevisionNumber(),
                                request.expectedCheckpointVersion(),
                                request.expectedEventHeadSequence(),
                                h0.mutationEventId()))
                && request.materializationSpec()
                        .sourceProjectVersion()
                        .equals(sourceProjectVersion)
                && owner.planId().equals(planId)
                && owner.materializationSpec().equals(
                        request.materializationSpec());
    }

    private static boolean isConfirmationConsistent(
            PlanId planId,
            InMemoryState.PlanExecutionContextReservationMarker reservation,
            InMemoryState.PlanExecutionContextConfirmationMarker confirmation) {
        if (confirmation.request() == null
                || confirmation.result() == null) {
            return false;
        }
        PlanExecutionContextConfirmationRequest request =
                confirmation.request();
        PersistedPlanExecutionContextConfirmed result =
                confirmation.result();
        return request.planId().equals(planId)
                && request.materializationSpec().equals(
                        reservation.request().materializationSpec())
                && request.materializationSpec().equals(
                        result.materializationSpec())
                && result.reservation().equals(reservation.result())
                && request.fencingToken() == result.fencingToken()
                && request.sourceManifestFingerprint().equals(
                        result.sourceManifestFingerprint());
    }

    enum Status {
        NONE,
        RESERVED,
        CONFIRMED,
        PARTIAL
    }

    enum ExecutionStatus {
        STRICT_PRE_START,
        AUTHORITATIVE,
        PARTIAL
    }

    record ContextCut(
            Status status,
            PersistedPlanExecutionContextReserved reservation,
            PersistedPlanExecutionContextConfirmed confirmation) {

        static ContextCut none() {
            return new ContextCut(Status.NONE, null, null);
        }

        static ContextCut reserved(
                PersistedPlanExecutionContextReserved reservation) {
            return new ContextCut(Status.RESERVED, reservation, null);
        }

        static ContextCut confirmed(
                PersistedPlanExecutionContextReserved reservation,
                PersistedPlanExecutionContextConfirmed confirmation) {
            return new ContextCut(
                    Status.CONFIRMED, reservation, confirmation);
        }

        static ContextCut partial() {
            return new ContextCut(Status.PARTIAL, null, null);
        }

        @Override
        public String toString() {
            return "ContextCut["
                    + "status=<provided>, "
                    + "reservation=<provided>, "
                    + "confirmation=<provided>]";
        }
    }

    private record OwnerLookup(
            InMemoryState.WorkspaceOwner owner,
            boolean partial) {

        static OwnerLookup valid(
                InMemoryState.WorkspaceOwner owner) {
            return new OwnerLookup(owner, false);
        }

        static OwnerLookup corrupt() {
            return new OwnerLookup(null, true);
        }
    }
}
