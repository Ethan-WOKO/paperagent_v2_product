package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class InMemoryPlanReplanRepository implements PlanReplanRepository {
    private static final String PARTIAL_PATH = "planReplan";
    private static final String ELIGIBILITY_PATH = "planReplan";

    private final InMemoryState state;

    InMemoryPlanReplanRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedPlanReplan> replan(PlanReplanRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            MarkerLookup existing = findMarker(
                    request.planId(), request.replanEvent().id());
            if (existing != null) {
                if (existing.corrupt()
                        || !InMemoryExecutionMutationAuthority
                                .hasValidPlanReplanReplayProvenance(
                                        state,
                                        request.planId(),
                                        request.replanEvent().id(),
                                        existing.marker())) {
                    return partialState();
                }
                return existing.marker().request().equals(request)
                        ? PersistenceResult.replayed(existing.marker().result())
                        : conflict();
            }

            Instant effectiveNow = state.observeLeaseTime();
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority
                            .validateAuthoritativeSource(state, request.planId());
            if (source == null) {
                return InMemoryExecutionMutationAuthority.hasPlanScopedOccupancy(
                                state, request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            PersistenceResult<PersistedPlanReplan> leaseFailure =
                    validateLiveLease(request, effectiveNow);
            if (leaseFailure != null) {
                return leaseFailure;
            }
            PersistenceResult<PersistedPlanReplan> stale =
                    validateExpectedSource(request, source);
            if (stale != null) {
                return stale;
            }
            if (!isEligible(source)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.PLAN_REPLAN_NOT_ELIGIBLE,
                        ELIGIBILITY_PATH);
            }
            PersistenceResult<PersistedPlanReplan> eventFailure =
                    validateEvent(request, source);
            if (eventFailure != null) {
                return eventFailure;
            }
            PlanValidation planValidation = validateReplannedPlan(request, source);
            if (planValidation == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                        "request.replannedRevision");
            }
            PersistenceResult<PersistedPlanReplan> checkpointFailure =
                    validateReplannedCheckpoint(request, source, planValidation.plan());
            if (checkpointFailure != null) {
                return checkpointFailure;
            }

            VersionedCheckpoint replanned = new VersionedCheckpoint(
                    request.expectedCheckpointVersion() + 1,
                    request.replannedCheckpoint());
            LeaseRecord lease = state.leases.get(request.planId());
            PersistedPlanReplan result = new PersistedPlanReplan(
                    request.planId(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    request.replanEvent(),
                    request.replannedRevision(),
                    replanned);
            InMemoryState.ExecutionMutationHead resultHead =
                    new InMemoryState.ExecutionMutationHead(
                            request.replannedRevision().id(),
                            request.replannedRevision().number(),
                            replanned.version(),
                            request.replanEvent().sequence(),
                            request.replanEvent().id());
            InMemoryState.ExecutionMutationLink link =
                    new InMemoryState.ExecutionMutationLink(
                            source.head(),
                            resultHead,
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .planReplan(request.replanEvent().id()));
            InMemoryState.PlanReplanMarker marker =
                    new InMemoryState.PlanReplanMarker(request, result, link);

            NavigableMap<Long, EventEnvelope> committedStream =
                    new TreeMap<>(source.eventStream());
            committedStream.put(request.replanEvent().sequence(),
                    request.replanEvent());
            Map<EventId, InMemoryState.PlanReplanMarker> committedMarkers =
                    new LinkedHashMap<>();
            Map<EventId, InMemoryState.PlanReplanMarker> existingMarkers =
                    state.planReplans.get(request.planId());
            if (existingMarkers != null) {
                committedMarkers.putAll(existingMarkers);
            }
            committedMarkers.put(request.replanEvent().id(), marker);
            List<InMemoryState.ExecutionMutationLink> committedLinks =
                    new ArrayList<>(source.links());
            committedLinks.add(link);

            state.plans.put(request.planId(), planValidation.plan());
            state.eventStreams.put(request.planId(), committedStream);
            state.eventsById.put(request.replanEvent().id(), request.replanEvent());
            state.checkpoints.put(request.planId(), replanned);
            state.planReplans.put(request.planId(), committedMarkers);
            state.executionMutationLinks.put(request.planId(), committedLinks);
            state.executionMutationHeads.put(request.planId(), resultHead);
            return PersistenceResult.applied(result);
        }
    }

    private MarkerLookup findMarker(
            io.paperagent.v2.contracts.PlanId planId,
            EventId eventId) {
        Map<EventId, InMemoryState.PlanReplanMarker> replans =
                state.planReplans.get(planId);
        InMemoryState.PlanReplanMarker replan = replans != null
                && replans.containsKey(eventId) ? replans.get(eventId) : null;
        boolean hasReplan = replans != null && replans.containsKey(eventId);
        boolean hasReplanLink = state.executionMutationLinks.get(planId) != null
                && state.executionMutationLinks.get(planId).stream()
                        .anyMatch(link -> link != null
                                && InMemoryState.ExecutionMutationMarkerIdentity
                                        .planReplan(eventId)
                                        .equals(link.markerIdentity()));
        int otherMatches = countOtherMarkers(planId, eventId);
        if (!hasReplan && !hasReplanLink && otherMatches == 0) {
            return null;
        }
        return new MarkerLookup(
                replan,
                !hasReplan || replan == null || !hasReplanLink || otherMatches != 0);
    }

    private int countOtherMarkers(
            io.paperagent.v2.contracts.PlanId planId,
            EventId eventId) {
        return contains(state.stepActivations.get(planId), eventId)
                + contains(state.stepCompletions.get(planId), eventId)
                + contains(state.stepPauses.get(planId), eventId)
                + contains(state.stepFailures.get(planId), eventId)
                + contains(state.stepCancellations.get(planId), eventId);
    }

    private static int contains(Map<EventId, ?> markers, EventId eventId) {
        return markers != null && markers.containsKey(eventId) ? 1 : 0;
    }

    private PersistenceResult<PersistedPlanReplan> validateLiveLease(
            PlanReplanRequest request,
            Instant effectiveNow) {
        LeaseRecord lease = state.leases.get(request.planId());
        if (lease == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
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
                    PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        }
        return null;
    }

    private static PersistenceResult<PersistedPlanReplan> validateExpectedSource(
            PlanReplanRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        PlanRevision latest = source.plan().latestRevision();
        if (!latest.id().equals(request.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (latest.number() != request.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (source.checkpoint().version() != request.expectedCheckpointVersion()) {
            return stale("request.expectedCheckpointVersion");
        }
        if (source.eventHeadSequence() != request.expectedEventHeadSequence()) {
            return stale("request.expectedEventHeadSequence");
        }
        return null;
    }

    private static boolean isEligible(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Checkpoint checkpoint = source.checkpoint().checkpoint();
        PlanRevision latest = source.plan().latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE) {
            return false;
        }
        for (PlanStep step : latest.steps()) {
            StepExecutionState expected = latest.completedFacts().containsKey(step.id())
                    ? StepExecutionState.SUCCEEDED
                    : StepExecutionState.NOT_STARTED;
            if (checkpoint.stepStates().get(step.id()) != expected) {
                return false;
            }
        }
        return checkpoint.stepStates().size() == latest.steps().size();
    }

    private PersistenceResult<PersistedPlanReplan> validateEvent(
            PlanReplanRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        EventEnvelope event = request.replanEvent();
        if (!event.planId().equals(request.planId())) {
            return PersistenceChecks.invalid("request.replanEvent.planId");
        }
        if (!event.taskFrameId().equals(source.taskFrame().id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.replanEvent.taskFrameId");
        }
        if (event.sequence() <= source.eventHeadSequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.replanEvent.sequence");
        }
        return state.eventsById.containsKey(event.id())
                ? conflict()
                : null;
    }

    private static PlanValidation validateReplannedPlan(
            PlanReplanRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Plan current = source.plan();
        PlanRevision previous = current.latestRevision();
        PlanRevision replanned = request.replannedRevision();
        if (replanned.number() != previous.number() + 1
                || !replanned.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || replanned.createdAt().isBefore(previous.createdAt())
                || !replanned.taskFrameId().equals(current.taskFrameId())
                || !replanned.completedFacts().equals(previous.completedFacts())) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(current.revisions());
        revisions.add(replanned);
        try {
            return new PlanValidation(new Plan(
                    current.id(), current.taskFrameId(), revisions));
        } catch (ContractViolationException invalid) {
            return null;
        }
    }

    private static PersistenceResult<PersistedPlanReplan>
            validateReplannedCheckpoint(
                    PlanReplanRequest request,
                    InMemoryExecutionMutationAuthority.AuthoritativeSource source,
                    Plan replannedPlan) {
        Checkpoint current = source.checkpoint().checkpoint();
        Checkpoint candidate = request.replannedCheckpoint();
        PlanRevision revision = request.replannedRevision();
        if (candidate.lastEventSequence() != request.replanEvent().sequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.replannedCheckpoint.lastEventSequence");
        }
        if (!candidate.taskFrameId().equals(current.taskFrameId())
                || !candidate.planId().equals(current.planId())
                || !candidate.revisionId().equals(revision.id())
                || candidate.revisionNumber() != revision.number()
                || candidate.createdAt().isBefore(current.createdAt())
                || !candidate.receiptReferences().equals(current.receiptReferences())
                || !hasExpectedStepShape(candidate, revision)
                || candidate.planState() != PlanExecutionState.ACTIVE
                || !CheckpointValidators.validate(
                                candidate,
                                source.taskFrame(),
                                replannedPlan,
                                current)
                        .isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.replannedCheckpoint");
        }
        return null;
    }

    private static boolean hasExpectedStepShape(
            Checkpoint checkpoint,
            PlanRevision revision) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        if (!checkpoint.stepStates().keySet().equals(stepIds)) {
            return false;
        }
        return revision.steps().stream().allMatch(step ->
                checkpoint.stepStates().get(step.id())
                        == (revision.completedFacts().containsKey(step.id())
                        ? StepExecutionState.SUCCEEDED
                        : StepExecutionState.NOT_STARTED));
    }

    private static PersistenceResult<PersistedPlanReplan> stale(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedPlanReplan> conflict() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.replanEvent.id");
    }

    private static PersistenceResult<PersistedPlanReplan> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.PLAN_REPLAN_PARTIAL_STATE, PARTIAL_PATH);
    }

    private record PlanValidation(Plan plan) {
    }

    private record MarkerLookup(
            InMemoryState.PlanReplanMarker marker,
            boolean corrupt) {
    }
}
