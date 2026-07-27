package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class InMemoryExecutionStartRepository implements ExecutionStartRepository {
    private final InMemoryState state;

    InMemoryExecutionStartRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedExecutionStart> start(
            ExecutionStartRequest request) {
        if (request == null) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            InMemoryState.ExecutionStartMarker marker =
                    state.executionStarts.get(request.planId());
            if (marker != null) {
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.planId");
            }

            Instant effectiveNow = state.observeLeaseTime();
            Plan plan = state.plans.get(request.planId());
            if (plan == null) {
                return hasPlanScopedOccupancy(request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            PersistedPlanBootstrap bootstrap =
                    state.planBootstraps.get(request.planId());
            TaskFrame taskFrame = state.taskFrames.get(plan.taskFrameId());
            if (!hasConsistentBootstrapRoot(plan, taskFrame, bootstrap)
                    || state.executionMutationHeads.containsKey(request.planId())
                    || state.executionMutationLinks.containsKey(request.planId())
                    || state.stepActivations.containsKey(request.planId())
                    || state.stepCompletions.containsKey(request.planId())
                    || state.stepPauses.containsKey(request.planId())
                    || state.stepFailures.containsKey(request.planId())
                    || state.stepCancellations.containsKey(request.planId())
                    || state.planExecutionContextReservations
                            .containsKey(request.planId())
                    || state.planExecutionContextConfirmations
                            .containsKey(request.planId())
                    || InMemoryPlanExecutionContextAuthority
                            .hasOwnerReference(state, request.planId())) {
                return partialState();
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

            VersionedCheckpoint source = state.checkpoints.get(request.planId());
            if (!bootstrap.initialCheckpoint().equals(source)) {
                return partialState();
            }

            PersistenceResult<PersistedExecutionStart> invalidTransition =
                    validateTransition(request, taskFrame, plan, source.checkpoint());
            if (invalidTransition != null) {
                return invalidTransition;
            }

            EventEnvelope existingById =
                    state.eventsById.get(request.startEvent().id());
            if (existingById != null
                    && !existingById.planId().equals(request.planId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.startEvent.id");
            }
            NavigableMap<Long, EventEnvelope> existingStream =
                    state.eventStreams.get(request.planId());
            if (existingById != null
                    || existingStream != null && !existingStream.isEmpty()) {
                return partialState();
            }

            VersionedCheckpoint started =
                    new VersionedCheckpoint(2, request.startedCheckpoint());
            PersistedExecutionStart result = new PersistedExecutionStart(
                    request.planId(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    request.startEvent(),
                    started);
            InMemoryState.ExecutionStartMarker committedMarker =
                    new InMemoryState.ExecutionStartMarker(request, result, plan);
            NavigableMap<Long, EventEnvelope> committedStream = new TreeMap<>();
            committedStream.put(1L, request.startEvent());

            state.eventStreams.put(request.planId(), committedStream);
            state.eventsById.put(request.startEvent().id(), request.startEvent());
            state.checkpoints.put(request.planId(), started);
            state.executionStarts.put(request.planId(), committedMarker);
            state.executionMutationHeads.put(
                    request.planId(),
                    InMemoryExecutionMutationAuthority.headFromStart(result));
            state.executionMutationLinks.put(request.planId(), List.of());
            state.stepActivations.put(
                    request.planId(), new LinkedHashMap<>());
            state.stepCompletions.put(
                    request.planId(), new LinkedHashMap<>());
            state.stepPauses.put(request.planId(), new LinkedHashMap<>());
            state.stepFailures.put(request.planId(), new LinkedHashMap<>());
            state.stepCancellations.put(request.planId(), new LinkedHashMap<>());
            return PersistenceResult.applied(result);
        }
    }

    private boolean hasPlanScopedOccupancy(PlanId planId) {
        return state.planBootstraps.containsKey(planId)
                || state.checkpoints.containsKey(planId)
                || state.eventStreams.containsKey(planId)
                || state.eventsById.values().stream()
                        .anyMatch(event -> event.planId().equals(planId))
                || state.leases.containsKey(planId)
                || state.fencingTokens.containsKey(planId)
                || state.executionMutationHeads.containsKey(planId)
                || state.executionMutationLinks.containsKey(planId)
                || state.stepActivations.containsKey(planId)
                || state.stepCompletions.containsKey(planId)
                || state.stepPauses.containsKey(planId)
                || state.stepFailures.containsKey(planId)
                || state.stepCancellations.containsKey(planId)
                || state.planExecutionContextReservations.containsKey(planId)
                || state.planExecutionContextConfirmations.containsKey(planId)
                || InMemoryPlanExecutionContextAuthority
                        .hasOwnerReference(state, planId);
    }

    private static boolean hasConsistentBootstrapRoot(
            Plan plan,
            TaskFrame taskFrame,
            PersistedPlanBootstrap bootstrap) {
        if (bootstrap == null
                || taskFrame == null
                || !bootstrap.taskFrame().equals(taskFrame)
                || !taskFrame.id().equals(plan.taskFrameId())
                || !bootstrap.plan().id().equals(plan.id())
                || !bootstrap.plan().taskFrameId().equals(plan.taskFrameId())) {
            return false;
        }
        List<?> bootstrapRevisions = bootstrap.plan().revisions();
        List<?> currentRevisions = plan.revisions();
        if (currentRevisions.size() < bootstrapRevisions.size()
                || !currentRevisions
                        .subList(0, bootstrapRevisions.size())
                        .equals(bootstrapRevisions)) {
            return false;
        }
        return isCanonicalBootstrapSource(
                bootstrap.initialCheckpoint(),
                bootstrap.taskFrame(),
                bootstrap.plan());
    }

    private static boolean isCanonicalBootstrapSource(
            VersionedCheckpoint source,
            TaskFrame taskFrame,
            Plan bootstrapPlan) {
        if (source.version() != 1) {
            return false;
        }
        Checkpoint checkpoint = source.checkpoint();
        PlanRevision revision = bootstrapPlan.latestRevision();
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.taskFrameId().equals(taskFrame.id())
                && checkpoint.planId().equals(bootstrapPlan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }

    private static PersistenceResult<PersistedExecutionStart> validateTransition(
            ExecutionStartRequest request,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint source) {
        EventEnvelope event = request.startEvent();
        if (!event.planId().equals(request.planId())) {
            return PersistenceChecks.invalid("request.startEvent.planId");
        }
        if (!event.taskFrameId().equals(taskFrame.id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.startEvent.taskFrameId");
        }
        if (event.sequence() != 1) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.startEvent.sequence");
        }

        Checkpoint candidate = request.startedCheckpoint();
        if (candidate.lastEventSequence() != 1) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.startedCheckpoint.lastEventSequence");
        }
        if (!isCanonicalTarget(candidate, taskFrame, plan)
                || !CheckpointValidators
                        .validate(candidate, taskFrame, plan, source)
                        .isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.startedCheckpoint");
        }
        return null;
    }

    private static boolean isCanonicalTarget(
            Checkpoint checkpoint,
            TaskFrame taskFrame,
            Plan plan) {
        PlanRevision latest = plan.latestRevision();
        Set<PlanStepId> latestStepIds = latest.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.planId().equals(plan.id())
                && checkpoint.taskFrameId().equals(taskFrame.id())
                && checkpoint.revisionId().equals(latest.id())
                && checkpoint.revisionNumber() == latest.number()
                && latest.completedFacts().isEmpty()
                && checkpoint.planState() == PlanExecutionState.ACTIVE
                && checkpoint.stepStates().keySet().equals(latestStepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }

    private static PersistenceResult<PersistedExecutionStart> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
    }
}
