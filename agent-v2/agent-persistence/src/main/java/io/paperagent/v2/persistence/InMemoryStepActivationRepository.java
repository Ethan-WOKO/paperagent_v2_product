package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class InMemoryStepActivationRepository
        implements StepActivationRepository {
    private static final String PARTIAL_PATH = "stepActivation";
    private static final String ELIGIBILITY_PATH = "stepActivation.source";

    private final InMemoryState state;

    InMemoryStepActivationRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedStepActivation> activate(
            StepActivationRequest request) {
        if (request == null) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            Map<EventId, InMemoryState.StepActivationMarker> planMarkers =
                    state.stepActivations.get(request.planId());
            if (planMarkers != null
                    && planMarkers.containsKey(request.activationEvent().id())) {
                InMemoryState.StepActivationMarker marker =
                        planMarkers.get(request.activationEvent().id());
                if (!InMemoryExecutionMutationAuthority.isSelfConsistentMarker(
                                request.planId(),
                                request.activationEvent().id(),
                                marker)) {
                    return partialState();
                }
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.activationEvent.id");
            }

            Instant effectiveNow = state.observeLeaseTime();
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority
                            .validateAuthoritativeSource(
                                    state, request.planId());
            if (source == null) {
                return InMemoryExecutionMutationAuthority
                                .hasPlanScopedOccupancy(
                                        state, request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            InMemoryPlanExecutionContextAuthority.ContextCut context =
                    InMemoryPlanExecutionContextAuthority.inspect(
                            state, request.planId(), source);
            if (context.status()
                    == InMemoryPlanExecutionContextAuthority.Status.PARTIAL) {
                return partialState();
            }
            if (source.taskFrame().sourceProjectVersion().isEmpty()) {
                if (context.status()
                        != InMemoryPlanExecutionContextAuthority.Status.NONE) {
                    return partialState();
                }
            } else if (context.status()
                    != InMemoryPlanExecutionContextAuthority.Status.CONFIRMED) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                        ELIGIBILITY_PATH);
            }

            LeaseRecord lease = state.leases.get(request.planId());
            if (lease == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId");
            }
            if (!lease.leaseToken().equals(request.leaseToken())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "request.leaseToken");
            }
            if (lease.fencingToken() != request.fencingToken()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                        "request.fencingToken");
            }
            if (lease.isExpiredAt(effectiveNow)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId");
            }

            PlanRevision latest = source.plan().latestRevision();
            if (!latest.id().equals(request.expectedRevisionId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionId");
            }
            if (latest.number() != request.expectedRevisionNumber()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionNumber");
            }
            if (source.checkpoint().version()
                    != request.expectedCheckpointVersion()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedCheckpointVersion");
            }
            if (source.eventHeadSequence()
                    != request.expectedEventHeadSequence()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedEventHeadSequence");
            }

            if (!isEligible(
                    source.plan(),
                    source.checkpoint().checkpoint(),
                    request.stepId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                        ELIGIBILITY_PATH);
            }

            EventEnvelope event = request.activationEvent();
            if (!event.planId().equals(request.planId())) {
                return PersistenceChecks.invalid(
                        "request.activationEvent.planId");
            }
            if (!event.taskFrameId().equals(source.taskFrame().id())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.TASK_FRAME_MISMATCH,
                        "request.activationEvent.taskFrameId");
            }
            if (event.sequence() <= source.eventHeadSequence()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                        "request.activationEvent.sequence");
            }
            if (state.eventsById.containsKey(event.id())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.activationEvent.id");
            }

            PersistenceResult<PersistedStepActivation> invalidTarget =
                    validateTarget(request, source);
            if (invalidTarget != null) {
                return invalidTarget;
            }

            VersionedCheckpoint activated = new VersionedCheckpoint(
                    request.expectedCheckpointVersion() + 1,
                    request.activatedCheckpoint());
            PersistedStepActivation result = new PersistedStepActivation(
                    request.planId(),
                    request.stepId(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    event,
                    activated);
            InMemoryState.ExecutionMutationHead resultHead =
                    new InMemoryState.ExecutionMutationHead(
                            latest.id(),
                            latest.number(),
                            activated.version(),
                            event.sequence(),
                            event.id());
            InMemoryState.ExecutionMutationLink link =
                    new InMemoryState.ExecutionMutationLink(
                            source.head(),
                            resultHead,
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .stepActivation(event.id()));
            InMemoryState.StepActivationMarker committedMarker =
                    new InMemoryState.StepActivationMarker(
                            request, result, link);

            NavigableMap<Long, EventEnvelope> committedStream =
                    new TreeMap<>(source.eventStream());
            committedStream.put(event.sequence(), event);
            Map<EventId, InMemoryState.StepActivationMarker> committedMarkers =
                    new java.util.LinkedHashMap<>(source.activationMarkers());
            committedMarkers.put(event.id(), committedMarker);
            List<InMemoryState.ExecutionMutationLink> committedLinks =
                    new java.util.ArrayList<>(source.links());
            committedLinks.add(link);

            state.eventStreams.put(request.planId(), committedStream);
            state.eventsById.put(event.id(), event);
            state.checkpoints.put(request.planId(), activated);
            state.stepActivations.put(request.planId(), committedMarkers);
            state.executionMutationLinks.put(request.planId(), committedLinks);
            state.executionMutationHeads.put(request.planId(), resultHead);
            return PersistenceResult.applied(result);
        }
    }

    static boolean isEligible(
            Plan plan,
            Checkpoint checkpoint,
            PlanStepId targetId) {
        PlanRevision revision = plan.latestRevision();
        PlanStep target = revision.steps().stream()
                .filter(step -> step.id().equals(targetId))
                .findFirst()
                .orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || checkpoint.stepStates().get(targetId)
                        != StepExecutionState.NOT_STARTED
                || revision.completedFacts().containsKey(targetId)) {
            return false;
        }
        for (PlanStepId dependency : target.dependencies()) {
            if (checkpoint.stepStates().get(dependency)
                            != StepExecutionState.SUCCEEDED
                    || !revision.completedFacts().containsKey(dependency)) {
                return false;
            }
        }
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                checkpoint.stepStates().entrySet()) {
            StepExecutionState value = entry.getValue();
            if (!entry.getKey().equals(targetId)
                            && (value == StepExecutionState.ACTIVE
                                    || value == StepExecutionState.PAUSED)
                    || value == StepExecutionState.FAILED
                    || value == StepExecutionState.CANCELLED) {
                return false;
            }
        }
        return true;
    }

    private static PersistenceResult<PersistedStepActivation> validateTarget(
            StepActivationRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Checkpoint current = source.checkpoint().checkpoint();
        Checkpoint target = request.activatedCheckpoint();
        if (target.lastEventSequence()
                != request.activationEvent().sequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.activatedCheckpoint.lastEventSequence");
        }
        if (!target.taskFrameId().equals(current.taskFrameId())
                || !target.planId().equals(current.planId())
                || !target.revisionId().equals(current.revisionId())
                || target.revisionNumber() != current.revisionNumber()
                || target.planState() != PlanExecutionState.ACTIVE
                || !target.receiptReferences().equals(
                        current.receiptReferences())
                || !target.stepStates().keySet().equals(
                        current.stepStates().keySet())
                || !hasOnlyTargetActivation(
                        current, target, request.stepId())
                || !CheckpointValidators.validate(
                                target,
                                source.taskFrame(),
                                source.plan(),
                                current)
                        .isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.activatedCheckpoint");
        }
        return null;
    }

    private static boolean hasOnlyTargetActivation(
            Checkpoint current,
            Checkpoint target,
            PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                current.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? StepExecutionState.ACTIVE
                    : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static PersistenceResult<PersistedStepActivation> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                PARTIAL_PATH);
    }
}
