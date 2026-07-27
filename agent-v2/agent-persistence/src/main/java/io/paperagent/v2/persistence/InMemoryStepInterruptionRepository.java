package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class InMemoryStepInterruptionRepository
        implements StepInterruptionRepository {
    private static final String PARTIAL_PATH = "stepInterruption";
    private static final String ELIGIBILITY_PATH = "stepInterruption";

    private final InMemoryState state;

    InMemoryStepInterruptionRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedStepInterruption> pause(
            StepPauseRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        return interrupt(new Candidate(
                StepInterruptionKind.PAUSE,
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.pauseEvent(),
                request.pausedCheckpoint(),
                request,
                "request.pauseEvent",
                "request.pausedCheckpoint"));
    }

    @Override
    public PersistenceResult<PersistedStepInterruption> fail(
            StepFailRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        return interrupt(new Candidate(
                StepInterruptionKind.FAIL,
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.failureEvent(),
                request.failedCheckpoint(),
                request,
                "request.failureEvent",
                "request.failedCheckpoint"));
    }

    @Override
    public PersistenceResult<PersistedStepInterruption> cancel(
            StepCancelRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        return interrupt(new Candidate(
                StepInterruptionKind.CANCEL,
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.cancellationEvent(),
                request.cancelledCheckpoint(),
                request,
                "request.cancellationEvent",
                "request.cancelledCheckpoint"));
    }

    private PersistenceResult<PersistedStepInterruption> interrupt(
            Candidate candidate) {
        synchronized (state.monitor) {
            MarkerLookup existing = findMarker(
                    candidate.planId(), candidate.event().id());
            if (existing != null) {
                if (!InMemoryExecutionMutationAuthority
                        .hasValidInterruptionReplayProvenance(
                                state,
                                candidate.planId(),
                                candidate.event().id(),
                                existing.kind(),
                                existing.marker())) {
                    return partialState();
                }
                return existing.request().equals(candidate.originalRequest())
                        ? PersistenceResult.replayed(existing.result())
                        : conflict(candidate.eventPath() + ".id");
            }

            Instant effectiveNow = state.observeLeaseTime();
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority.validateAuthoritativeSource(
                            state, candidate.planId());
            if (source == null) {
                return InMemoryExecutionMutationAuthority.hasPlanScopedOccupancy(
                                state, candidate.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            PersistenceResult<PersistedStepInterruption> leaseFailure =
                    validateLiveLease(candidate, effectiveNow);
            if (leaseFailure != null) {
                return leaseFailure;
            }
            PersistenceResult<PersistedStepInterruption> stale =
                    validateExpectedSource(candidate, source);
            if (stale != null) {
                return stale;
            }
            if (!isEligible(source, candidate.stepId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                        ELIGIBILITY_PATH);
            }
            if (!hasCurrentActivation(source, candidate.stepId())) {
                return partialState();
            }
            PersistenceResult<PersistedStepInterruption> eventFailure =
                    validateEvent(candidate, source);
            if (eventFailure != null) {
                return eventFailure;
            }
            PersistenceResult<PersistedStepInterruption> checkpointFailure =
                    validateCheckpoint(candidate, source);
            if (checkpointFailure != null) {
                return checkpointFailure;
            }

            VersionedCheckpoint interrupted = new VersionedCheckpoint(
                    candidate.expectedCheckpointVersion() + 1,
                    candidate.checkpoint());
            LeaseRecord lease = state.leases.get(candidate.planId());
            PersistedStepInterruption result = new PersistedStepInterruption(
                    candidate.planId(),
                    candidate.stepId(),
                    candidate.kind(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    candidate.event(),
                    interrupted);
            InMemoryState.ExecutionMutationHead resultHead =
                    new InMemoryState.ExecutionMutationHead(
                            source.head().revisionId(),
                            source.head().revisionNumber(),
                            interrupted.version(),
                            candidate.event().sequence(),
                            candidate.event().id());
            InMemoryState.ExecutionMutationLink link =
                    new InMemoryState.ExecutionMutationLink(
                            source.head(),
                            resultHead,
                            markerIdentity(candidate.kind(), candidate.event().id()));

            NavigableMap<Long, EventEnvelope> committedStream =
                    new TreeMap<>(source.eventStream());
            committedStream.put(candidate.event().sequence(), candidate.event());
            List<InMemoryState.ExecutionMutationLink> committedLinks =
                    new ArrayList<>(source.links());
            committedLinks.add(link);
            persistMarker(candidate, result, link);
            state.eventStreams.put(candidate.planId(), committedStream);
            state.eventsById.put(candidate.event().id(), candidate.event());
            state.checkpoints.put(candidate.planId(), interrupted);
            state.executionMutationLinks.put(candidate.planId(), committedLinks);
            state.executionMutationHeads.put(candidate.planId(), resultHead);
            return PersistenceResult.applied(result);
        }
    }

    private void persistMarker(
            Candidate candidate,
            PersistedStepInterruption result,
            InMemoryState.ExecutionMutationLink link) {
        switch (candidate.kind()) {
            case PAUSE -> {
                Map<EventId, InMemoryState.StepPauseMarker> markers =
                        new LinkedHashMap<>(state.stepPauses.get(candidate.planId()));
                markers.put(candidate.event().id(), new InMemoryState.StepPauseMarker(
                        (StepPauseRequest) candidate.originalRequest(), result, link));
                state.stepPauses.put(candidate.planId(), markers);
            }
            case FAIL -> {
                Map<EventId, InMemoryState.StepFailMarker> markers =
                        new LinkedHashMap<>(state.stepFailures.get(candidate.planId()));
                markers.put(candidate.event().id(), new InMemoryState.StepFailMarker(
                        (StepFailRequest) candidate.originalRequest(), result, link));
                state.stepFailures.put(candidate.planId(), markers);
            }
            case CANCEL -> {
                Map<EventId, InMemoryState.StepCancelMarker> markers =
                        new LinkedHashMap<>(state.stepCancellations.get(candidate.planId()));
                markers.put(candidate.event().id(), new InMemoryState.StepCancelMarker(
                        (StepCancelRequest) candidate.originalRequest(), result, link));
                state.stepCancellations.put(candidate.planId(), markers);
            }
        }
    }

    private MarkerLookup findMarker(PlanId planId, EventId eventId) {
        List<MarkerLookup> matches = new ArrayList<>();
        addPauseMarker(matches, state.stepPauses.get(planId), eventId);
        addFailMarker(matches, state.stepFailures.get(planId), eventId);
        addCancelMarker(matches, state.stepCancellations.get(planId), eventId);
        return matches.isEmpty()
                ? null
                : matches.size() == 1
                        ? matches.get(0)
                        : MarkerLookup.corrupt();
    }

    private static void addPauseMarker(
            List<MarkerLookup> matches,
            Map<EventId, InMemoryState.StepPauseMarker> markers,
            EventId eventId) {
        if (markers != null && markers.containsKey(eventId)) {
            InMemoryState.StepPauseMarker marker = markers.get(eventId);
            matches.add(new MarkerLookup(
                    StepInterruptionKind.PAUSE,
                    marker == null ? null : marker.request(),
                    marker == null ? null : marker.result(),
                    marker,
                    false));
        }
    }

    private static void addFailMarker(
            List<MarkerLookup> matches,
            Map<EventId, InMemoryState.StepFailMarker> markers,
            EventId eventId) {
        if (markers != null && markers.containsKey(eventId)) {
            InMemoryState.StepFailMarker marker = markers.get(eventId);
            matches.add(new MarkerLookup(
                    StepInterruptionKind.FAIL,
                    marker == null ? null : marker.request(),
                    marker == null ? null : marker.result(),
                    marker,
                    false));
        }
    }

    private static void addCancelMarker(
            List<MarkerLookup> matches,
            Map<EventId, InMemoryState.StepCancelMarker> markers,
            EventId eventId) {
        if (markers != null && markers.containsKey(eventId)) {
            InMemoryState.StepCancelMarker marker = markers.get(eventId);
            matches.add(new MarkerLookup(
                    StepInterruptionKind.CANCEL,
                    marker == null ? null : marker.request(),
                    marker == null ? null : marker.result(),
                    marker,
                    false));
        }
    }

    private PersistenceResult<PersistedStepInterruption> validateLiveLease(
            Candidate candidate,
            Instant effectiveNow) {
        LeaseRecord lease = state.leases.get(candidate.planId());
        if (lease == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        }
        if (!lease.leaseToken().equals(candidate.leaseToken())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID, "request.leaseToken");
        }
        if (lease.fencingToken() != candidate.fencingToken()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (lease.isExpiredAt(effectiveNow)) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        }
        return null;
    }

    private static PersistenceResult<PersistedStepInterruption> validateExpectedSource(
            Candidate candidate,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        PlanRevision latest = source.plan().latestRevision();
        if (!latest.id().equals(candidate.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (latest.number() != candidate.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (source.checkpoint().version() != candidate.expectedCheckpointVersion()) {
            return stale("request.expectedCheckpointVersion");
        }
        if (source.eventHeadSequence() != candidate.expectedEventHeadSequence()) {
            return stale("request.expectedEventHeadSequence");
        }
        return null;
    }

    private static boolean hasCurrentActivation(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            PlanStepId stepId) {
        InMemoryState.StepActivationMarker marker = source.activationMarkers()
                .get(source.head().mutationEventId());
        return marker != null
                && InMemoryExecutionMutationAuthority.isSelfConsistentMarker(
                        source.plan().id(), source.head().mutationEventId(), marker)
                && marker.result().stepId().equals(stepId);
    }

    private static boolean isEligible(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            PlanStepId stepId) {
        PlanRevision latest = source.plan().latestRevision();
        Checkpoint checkpoint = source.checkpoint().checkpoint();
        PlanStep target = latest.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst()
                .orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || checkpoint.stepStates().get(stepId) != StepExecutionState.ACTIVE
                || latest.completedFacts().containsKey(stepId)) {
            return false;
        }
        int active = 0;
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                checkpoint.stepStates().entrySet()) {
            StepExecutionState stepState = entry.getValue();
            if (stepState == StepExecutionState.ACTIVE) {
                active++;
            }
            if (!entry.getKey().equals(stepId)
                    && stepState != StepExecutionState.NOT_STARTED
                    && stepState != StepExecutionState.SUCCEEDED) {
                return false;
            }
        }
        return active == 1;
    }

    private PersistenceResult<PersistedStepInterruption> validateEvent(
            Candidate candidate,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        EventEnvelope event = candidate.event();
        if (!event.planId().equals(candidate.planId())) {
            return PersistenceChecks.invalid(candidate.eventPath() + ".planId");
        }
        if (!event.taskFrameId().equals(source.taskFrame().id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    candidate.eventPath() + ".taskFrameId");
        }
        if (event.sequence() <= source.eventHeadSequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    candidate.eventPath() + ".sequence");
        }
        return state.eventsById.containsKey(event.id())
                ? conflict(candidate.eventPath() + ".id")
                : null;
    }

    private static PersistenceResult<PersistedStepInterruption> validateCheckpoint(
            Candidate candidate,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Checkpoint current = source.checkpoint().checkpoint();
        Checkpoint checkpoint = candidate.checkpoint();
        if (checkpoint.lastEventSequence() != candidate.event().sequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    candidate.checkpointPath() + ".lastEventSequence");
        }
        if (!checkpoint.taskFrameId().equals(current.taskFrameId())
                || !checkpoint.planId().equals(current.planId())
                || !checkpoint.revisionId().equals(current.revisionId())
                || checkpoint.revisionNumber() != current.revisionNumber()
                || checkpoint.createdAt().isBefore(current.createdAt())
                || !checkpoint.receiptReferences().equals(current.receiptReferences())
                || !checkpoint.stepStates().keySet().equals(current.stepStates().keySet())
                || !hasOnlyTargetInterruption(current, checkpoint,
                        candidate.stepId(), candidate.kind())
                || checkpoint.planState() != planState(candidate.kind())
                || !CheckpointValidators.validate(
                                checkpoint,
                                source.taskFrame(),
                                source.plan(),
                                current)
                        .isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    candidate.checkpointPath());
        }
        return null;
    }

    private static boolean hasOnlyTargetInterruption(
            Checkpoint current,
            Checkpoint candidate,
            PlanStepId targetId,
            StepInterruptionKind kind) {
        StepExecutionState targetState = stepState(kind);
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                current.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? targetState
                    : entry.getValue();
            if (candidate.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static InMemoryState.ExecutionMutationMarkerIdentity markerIdentity(
            StepInterruptionKind kind,
            EventId eventId) {
        return switch (kind) {
            case PAUSE -> InMemoryState.ExecutionMutationMarkerIdentity.stepPause(eventId);
            case FAIL -> InMemoryState.ExecutionMutationMarkerIdentity.stepFail(eventId);
            case CANCEL -> InMemoryState.ExecutionMutationMarkerIdentity.stepCancel(eventId);
        };
    }

    private static StepExecutionState stepState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    private static PlanExecutionState planState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    private static PersistenceResult<PersistedStepInterruption> stale(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedStepInterruption> conflict(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static PersistenceResult<PersistedStepInterruption> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE, PARTIAL_PATH);
    }

    private record Candidate(
            StepInterruptionKind kind,
            PlanId planId,
            String leaseToken,
            long fencingToken,
            io.paperagent.v2.contracts.PlanRevisionId expectedRevisionId,
            long expectedRevisionNumber,
            long expectedCheckpointVersion,
            long expectedEventHeadSequence,
            PlanStepId stepId,
            EventEnvelope event,
            Checkpoint checkpoint,
            Object originalRequest,
            String eventPath,
            String checkpointPath) {
    }

    private record MarkerLookup(
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result,
            Object marker,
            boolean duplicate) {

        static MarkerLookup corrupt() {
            return new MarkerLookup(null, null, null, null, true);
        }
    }
}
