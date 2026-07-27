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

final class InMemoryActiveStepReplanRepository
        implements ActiveStepReplanRepository {
    private static final String PARTIAL_PATH = "activeStepReplan";
    private static final String ELIGIBILITY_PATH = "activeStepReplan.source";

    private final InMemoryState state;

    InMemoryActiveStepReplanRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedActiveStepReplan> supersedeAndReplan(
            ActiveStepReplanRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            MarkerLookup existing = findMarker(
                    request.planId(),
                    request.supersessionEvent().id(),
                    request.replanEvent().id());
            if (existing != null) {
                if (existing.corrupt()
                        || !InMemoryExecutionMutationAuthority
                                .hasValidActiveStepReplanReplayProvenance(
                                        state,
                                        request.planId(),
                                        existing.markerKey(),
                                        existing.marker())) {
                    return partialState();
                }
                return existing.marker().request().equals(request)
                        ? PersistenceResult.replayed(existing.marker().result())
                        : conflict(existing.conflictPath());
            }
            if (state.eventsById.containsKey(request.supersessionEvent().id())) {
                return conflict("request.supersessionEvent.id");
            }
            if (state.eventsById.containsKey(request.replanEvent().id())) {
                return conflict("request.replanEvent.id");
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
            PersistenceResult<PersistedActiveStepReplan> leaseFailure =
                    validateLiveLease(request, effectiveNow);
            if (leaseFailure != null) {
                return leaseFailure;
            }
            PersistenceResult<PersistedActiveStepReplan> stale =
                    validateExpectedSource(request, source);
            if (stale != null) {
                return stale;
            }
            if (!InMemoryExecutionMutationAuthority.isEligibleActiveStepReplanSource(
                    source.plan(), source.checkpoint().checkpoint(), request.activeStepId())) {
                return notEligible();
            }
            PersistenceResult<PersistedActiveStepReplan> activationFailure =
                    validateSelectedActivation(request, source);
            if (activationFailure != null) {
                return activationFailure;
            }
            PersistenceResult<PersistedActiveStepReplan> effectFailure =
                    validateSelectedStepEffects(request);
            if (effectFailure != null) {
                return effectFailure;
            }
            PersistenceResult<PersistedActiveStepReplan> eventFailure =
                    validateEvents(request, source);
            if (eventFailure != null) {
                return eventFailure;
            }
            PlanValidation planValidation = validateReplannedPlan(request, source);
            if (planValidation == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                        "request.replannedRevision");
            }
            PersistenceResult<PersistedActiveStepReplan> supersededFailure =
                    validateSupersededCheckpoint(request, source);
            if (supersededFailure != null) {
                return supersededFailure;
            }
            PersistenceResult<PersistedActiveStepReplan> replannedFailure =
                    validateReplannedCheckpoint(request, source, planValidation.plan());
            if (replannedFailure != null) {
                return replannedFailure;
            }

            VersionedCheckpoint superseded = new VersionedCheckpoint(
                    request.expectedCheckpointVersion() + 1,
                    request.supersededCheckpoint());
            VersionedCheckpoint replanned = new VersionedCheckpoint(
                    request.expectedCheckpointVersion() + 2,
                    request.replannedCheckpoint());
            LeaseRecord lease = state.leases.get(request.planId());
            PersistedActiveStepReplan result = new PersistedActiveStepReplan(
                    request.planId(),
                    request.activeStepId(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    request.supersessionEvent(),
                    superseded,
                    request.replanEvent(),
                    request.replannedRevision(),
                    replanned);
            InMemoryState.ExecutionMutationHead supersededHead =
                    new InMemoryState.ExecutionMutationHead(
                            source.plan().latestRevision().id(),
                            source.plan().latestRevision().number(),
                            superseded.version(),
                            request.supersessionEvent().sequence(),
                            request.supersessionEvent().id());
            InMemoryState.ExecutionMutationHead replannedHead =
                    new InMemoryState.ExecutionMutationHead(
                            request.replannedRevision().id(),
                            request.replannedRevision().number(),
                            replanned.version(),
                            request.replanEvent().sequence(),
                            request.replanEvent().id());
            InMemoryState.ExecutionMutationLink supersessionLink =
                    new InMemoryState.ExecutionMutationLink(
                            source.head(),
                            supersededHead,
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .activeStepReplanSupersession(
                                            request.supersessionEvent().id()));
            InMemoryState.ExecutionMutationLink replanLink =
                    new InMemoryState.ExecutionMutationLink(
                            supersededHead,
                            replannedHead,
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .activeStepReplanReplan(
                                            request.replanEvent().id()));
            InMemoryState.ActiveStepReplanMarker marker =
                    new InMemoryState.ActiveStepReplanMarker(
                            request, result, supersessionLink, replanLink);

            NavigableMap<Long, EventEnvelope> committedStream =
                    new TreeMap<>(source.eventStream());
            committedStream.put(
                    request.supersessionEvent().sequence(), request.supersessionEvent());
            committedStream.put(request.replanEvent().sequence(), request.replanEvent());
            Map<EventId, InMemoryState.ActiveStepReplanMarker> committedMarkers =
                    new LinkedHashMap<>();
            Map<EventId, InMemoryState.ActiveStepReplanMarker> existingMarkers =
                    state.activeStepReplans.get(request.planId());
            if (existingMarkers != null) {
                committedMarkers.putAll(existingMarkers);
            }
            committedMarkers.put(request.supersessionEvent().id(), marker);
            List<InMemoryState.ExecutionMutationLink> committedLinks =
                    new ArrayList<>(source.links());
            committedLinks.add(supersessionLink);
            committedLinks.add(replanLink);

            // The adapter monitor is the transaction boundary for this composite fact.
            state.plans.put(request.planId(), planValidation.plan());
            state.eventStreams.put(request.planId(), committedStream);
            state.eventsById.put(
                    request.supersessionEvent().id(), request.supersessionEvent());
            state.eventsById.put(request.replanEvent().id(), request.replanEvent());
            state.checkpoints.put(request.planId(), replanned);
            state.activeStepReplans.put(request.planId(), committedMarkers);
            state.executionMutationLinks.put(request.planId(), committedLinks);
            state.executionMutationHeads.put(request.planId(), replannedHead);
            return PersistenceResult.applied(result);
        }
    }

    private MarkerLookup findMarker(
            io.paperagent.v2.contracts.PlanId planId,
            EventId supersessionEventId,
            EventId replanEventId) {
        Map<EventId, InMemoryState.ActiveStepReplanMarker> markers =
                state.activeStepReplans.get(planId);
        EventId markerKey = null;
        InMemoryState.ActiveStepReplanMarker found = null;
        boolean multiple = false;
        if (markers != null) {
            for (Map.Entry<EventId, InMemoryState.ActiveStepReplanMarker> entry
                    : markers.entrySet()) {
                InMemoryState.ActiveStepReplanMarker marker = entry.getValue();
                if (marker == null || marker.request() == null) {
                    continue;
                }
                ActiveStepReplanRequest stored = marker.request();
                if (supersessionEventId.equals(entry.getKey())
                        || replanEventId.equals(entry.getKey())
                        || supersessionEventId.equals(stored.supersessionEvent().id())
                        || supersessionEventId.equals(stored.replanEvent().id())
                        || replanEventId.equals(stored.supersessionEvent().id())
                        || replanEventId.equals(stored.replanEvent().id())) {
                    if (found != null && found != marker) {
                        multiple = true;
                    }
                    found = marker;
                    markerKey = entry.getKey();
                }
            }
        }
        boolean supersessionLink = hasLink(
                planId,
                InMemoryState.ExecutionMutationMarkerIdentity
                        .activeStepReplanSupersession(supersessionEventId));
        boolean replanLink = hasLink(
                planId,
                InMemoryState.ExecutionMutationMarkerIdentity
                        .activeStepReplanReplan(replanEventId));
        if (found == null) {
            return supersessionLink || replanLink
                    ? new MarkerLookup(null, null, true, "request.supersessionEvent.id")
                    : null;
        }
        boolean hasBothLinks = hasLink(
                planId,
                found.supersessionProvenanceLink() == null
                        ? null
                        : found.supersessionProvenanceLink().markerIdentity())
                && hasLink(
                        planId,
                        found.replanProvenanceLink() == null
                                ? null
                                : found.replanProvenanceLink().markerIdentity());
        return new MarkerLookup(
                markerKey,
                found,
                multiple || markerKey == null || !hasBothLinks,
                supersessionEventId.equals(markerKey)
                        ? "request.replanEvent.id"
                        : "request.supersessionEvent.id");
    }

    private boolean hasLink(
            io.paperagent.v2.contracts.PlanId planId,
            InMemoryState.ExecutionMutationMarkerIdentity identity) {
        if (identity == null || state.executionMutationLinks.get(planId) == null) {
            return false;
        }
        return state.executionMutationLinks.get(planId).stream()
                .anyMatch(link -> link != null && identity.equals(link.markerIdentity()));
    }

    private PersistenceResult<PersistedActiveStepReplan> validateLiveLease(
            ActiveStepReplanRequest request,
            Instant effectiveNow) {
        LeaseRecord lease = state.leases.get(request.planId());
        if (lease == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        }
        if (!lease.leaseToken().equals(request.leaseToken())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID, "request.leaseToken");
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

    private static PersistenceResult<PersistedActiveStepReplan> validateExpectedSource(
            ActiveStepReplanRequest request,
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

    private static PersistenceResult<PersistedActiveStepReplan>
            validateSelectedActivation(
                    ActiveStepReplanRequest request,
                    InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        EventId activationEventId = source.head().mutationEventId();
        InMemoryState.StepActivationMarker activation = source.activationMarkers()
                .get(activationEventId);
        if (activation == null
                || !InMemoryExecutionMutationAuthority.isSelfConsistentMarker(
                        request.planId(), activationEventId, activation)) {
            return partialState();
        }
        return activation.result().stepId().equals(request.activeStepId())
                ? null
                : notEligible();
    }

    private PersistenceResult<PersistedActiveStepReplan> validateSelectedStepEffects(
            ActiveStepReplanRequest request) {
        boolean missingFinalResult = false;
        for (Map.Entry<io.paperagent.v2.contracts.ToolCallId,
                InMemoryState.EffectIntentMarker> entry : state.effectIntents.entrySet()) {
            InMemoryState.EffectIntentMarker marker = entry.getValue();
            if (entry.getKey() == null || marker == null || marker.request() == null
                    || marker.request().intent() == null) {
                return partialState();
            }
            io.paperagent.v2.contracts.EffectIntent intent = marker.request().intent();
            if (!request.planId().equals(intent.planId())
                    || !request.activeStepId().equals(intent.stepId())) {
                continue;
            }
            if (!InMemoryEffectIntentRepository.isIntactMarker(entry.getKey(), marker)) {
                return partialState();
            }
            InMemoryState.EffectResultMarker result =
                    state.effectResults.get(entry.getKey());
            if (result == null) {
                if (state.effectResults.containsKey(entry.getKey())) {
                    return partialState();
                }
                missingFinalResult = true;
                continue;
            }
            if (!isIntactFinalResultMarker(entry.getKey(), result)) {
                return partialState();
            }
        }
        for (Map.Entry<io.paperagent.v2.contracts.ToolCallId,
                NavigableMap<Long, InMemoryState.EffectProgressMarker>> entry
                : state.effectProgresses.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !state.effectIntents.containsKey(entry.getKey())) {
                return partialState();
            }
        }
        for (Map.Entry<io.paperagent.v2.contracts.ToolCallId,
                InMemoryState.EffectResultMarker> entry : state.effectResults.entrySet()) {
            if (entry.getKey() == null
                    || !state.effectIntents.containsKey(entry.getKey())
                    || !isIntactFinalResultMarker(entry.getKey(), entry.getValue())) {
                return partialState();
            }
        }
        return missingFinalResult ? notEligible() : null;
    }

    private boolean isIntactFinalResultMarker(
            io.paperagent.v2.contracts.ToolCallId toolCallId,
            InMemoryState.EffectResultMarker marker) {
        if (!InMemoryEffectOutcomeRepository.isIntactResultMarker(
                state, toolCallId, marker)) {
            return false;
        }
        int receiptsForIntent = 0;
        for (Map.Entry<io.paperagent.v2.contracts.ReceiptId,
                io.paperagent.v2.contracts.ExecutionReceipt> entry : state.receipts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !entry.getKey().equals(entry.getValue().id())) {
                return false;
            }
            if (toolCallId.equals(entry.getValue().toolCallId())) {
                receiptsForIntent++;
                if (!entry.getValue().equals(marker.request().receipt())) {
                    return false;
                }
            }
        }
        return receiptsForIntent == 1;
    }

    private PersistenceResult<PersistedActiveStepReplan> validateEvents(
            ActiveStepReplanRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        EventEnvelope supersession = request.supersessionEvent();
        EventEnvelope replan = request.replanEvent();
        if (supersession.id().equals(replan.id())) {
            return PersistenceChecks.invalid("request.replanEvent.id");
        }
        if (!supersession.planId().equals(request.planId())) {
            return PersistenceChecks.invalid("request.supersessionEvent.planId");
        }
        if (!replan.planId().equals(request.planId())) {
            return PersistenceChecks.invalid("request.replanEvent.planId");
        }
        if (!supersession.taskFrameId().equals(source.taskFrame().id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.supersessionEvent.taskFrameId");
        }
        if (!replan.taskFrameId().equals(source.taskFrame().id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.replanEvent.taskFrameId");
        }
        if (supersession.sequence() <= source.eventHeadSequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.supersessionEvent.sequence");
        }
        if (replan.sequence() <= supersession.sequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.replanEvent.sequence");
        }
        EventEnvelope headEvent = source.eventStream().lastEntry().getValue();
        if (supersession.occurredAt().isBefore(headEvent.occurredAt())) {
            return PersistenceChecks.invalid("request.supersessionEvent.occurredAt");
        }
        if (replan.occurredAt().isBefore(supersession.occurredAt())) {
            return PersistenceChecks.invalid("request.replanEvent.occurredAt");
        }
        if (state.eventsById.containsKey(supersession.id())) {
            return conflict("request.supersessionEvent.id");
        }
        return state.eventsById.containsKey(replan.id())
                ? conflict("request.replanEvent.id")
                : null;
    }

    private static PlanValidation validateReplannedPlan(
            ActiveStepReplanRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Plan current = source.plan();
        PlanRevision previous = current.latestRevision();
        PlanRevision replanned = request.replannedRevision();
        if (replanned.number() != previous.number() + 1
                || !replanned.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || replanned.createdAt().isBefore(previous.createdAt())
                || !replanned.taskFrameId().equals(current.taskFrameId())
                || !replanned.completedFacts().equals(previous.completedFacts())
                || replanned.steps().stream().anyMatch(step ->
                        step.id().equals(request.activeStepId()))) {
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

    private static PersistenceResult<PersistedActiveStepReplan>
            validateSupersededCheckpoint(
                    ActiveStepReplanRequest request,
                    InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Checkpoint current = source.checkpoint().checkpoint();
        Checkpoint target = request.supersededCheckpoint();
        if (target.lastEventSequence() != request.supersessionEvent().sequence()) {
            return checkpointFailure("request.supersededCheckpoint.lastEventSequence");
        }
        if (!target.taskFrameId().equals(current.taskFrameId())
                || !target.planId().equals(current.planId())
                || !target.revisionId().equals(current.revisionId())
                || target.revisionNumber() != current.revisionNumber()
                || target.createdAt().isBefore(current.createdAt())
                || target.planState() != PlanExecutionState.ACTIVE
                || !target.receiptReferences().equals(current.receiptReferences())
                || !target.stepStates().keySet().equals(current.stepStates().keySet())
                || !hasOnlySupersededStep(current, target, request.activeStepId())
                || !CheckpointValidators.validate(
                                target,
                                source.taskFrame(),
                                source.plan(),
                                current)
                        .isEmpty()) {
            return checkpointFailure("request.supersededCheckpoint");
        }
        return null;
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            validateReplannedCheckpoint(
                    ActiveStepReplanRequest request,
                    InMemoryExecutionMutationAuthority.AuthoritativeSource source,
                    Plan replannedPlan) {
        Checkpoint superseded = request.supersededCheckpoint();
        Checkpoint target = request.replannedCheckpoint();
        PlanRevision revision = request.replannedRevision();
        if (target.lastEventSequence() != request.replanEvent().sequence()) {
            return checkpointFailure("request.replannedCheckpoint.lastEventSequence");
        }
        if (!target.taskFrameId().equals(superseded.taskFrameId())
                || !target.planId().equals(superseded.planId())
                || !target.revisionId().equals(revision.id())
                || target.revisionNumber() != revision.number()
                || target.createdAt().isBefore(superseded.createdAt())
                || target.planState() != PlanExecutionState.ACTIVE
                || !target.receiptReferences().equals(superseded.receiptReferences())
                || !hasExpectedStepShape(target, revision)
                || !CheckpointValidators.validate(
                                target,
                                source.taskFrame(),
                                replannedPlan,
                                superseded)
                        .isEmpty()) {
            return checkpointFailure("request.replannedCheckpoint");
        }
        return null;
    }

    private static boolean hasOnlySupersededStep(
            Checkpoint current,
            Checkpoint target,
            PlanStepId activeStepId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                current.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(activeStepId)
                    ? StepExecutionState.SUPERSEDED_BY_REPLAN
                    : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
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

    private static PersistenceResult<PersistedActiveStepReplan> stale(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedActiveStepReplan> checkpointFailure(
            String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED, path);
    }

    private static PersistenceResult<PersistedActiveStepReplan> notEligible() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                ELIGIBILITY_PATH);
    }

    private static PersistenceResult<PersistedActiveStepReplan> conflict(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static PersistenceResult<PersistedActiveStepReplan> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_PARTIAL_STATE, PARTIAL_PATH);
    }

    private record PlanValidation(Plan plan) {
    }

    private record MarkerLookup(
            EventId markerKey,
            InMemoryState.ActiveStepReplanMarker marker,
            boolean corrupt,
            String conflictPath) {
    }
}
