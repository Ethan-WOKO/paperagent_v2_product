package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class InMemoryStepCompletionRepository
        implements StepCompletionRepository {
    private static final String PARTIAL_PATH = "stepCompletion";
    private static final String ELIGIBILITY_PATH = "stepCompletion";
    private static final String EFFECT_OUTCOMES_PATH =
            "stepCompletion.effectOutcomes";

    private final InMemoryState state;

    InMemoryStepCompletionRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedStepCompletion> complete(
            StepCompletionRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            InMemoryState.StepCompletionMarker existing = completionMarker(
                    request.planId(), request.completionEvent().id());
            if (existing != null) {
                if (!InMemoryExecutionMutationAuthority
                        .isSelfConsistentCompletionMarker(
                                request.planId(),
                                request.completionEvent().id(),
                                existing)) {
                    return partialState();
                }
                return existing.request().equals(request)
                        ? PersistenceResult.replayed(existing.result())
                        : conflict();
            }

            Instant effectiveNow = state.observeLeaseTime();
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority
                            .validateAuthoritativeSource(state, request.planId());
            if (source == null) {
                return InMemoryExecutionMutationAuthority
                                .hasPlanScopedOccupancy(state, request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }
            PersistenceResult<PersistedStepCompletion> leaseFailure =
                    validateLiveLease(request, effectiveNow);
            if (leaseFailure != null) {
                return leaseFailure;
            }
            PersistenceResult<PersistedStepCompletion> stale =
                    validateExpectedSource(request, source);
            if (stale != null) {
                return stale;
            }
            if (!hasCurrentActivation(source, request.stepId())) {
                return partialState();
            }
            if (!isEligible(source, request)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                        ELIGIBILITY_PATH);
            }
            PersistenceResult<PersistedStepCompletion> evidenceFailure =
                    validateEffectEvidence(source, request);
            if (evidenceFailure != null) {
                return evidenceFailure;
            }
            PersistenceResult<PersistedStepCompletion> eventFailure =
                    validateEvent(request, source);
            if (eventFailure != null) {
                return eventFailure;
            }
            PlanValidation planValidation = validateCompletedPlan(request, source);
            if (planValidation == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                        "request.completedRevision");
            }
            PersistenceResult<PersistedStepCompletion> checkpointFailure =
                    validateCompletedCheckpoint(request, source, planValidation.plan());
            if (checkpointFailure != null) {
                return checkpointFailure;
            }

            VersionedCheckpoint completed = new VersionedCheckpoint(
                    request.expectedCheckpointVersion() + 1,
                    request.completedCheckpoint());
            LeaseRecord lease = state.leases.get(request.planId());
            PersistedStepCompletion result = new PersistedStepCompletion(
                    request.planId(),
                    request.stepId(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    request.completionEvent(),
                    request.completedRevision(),
                    completed);
            InMemoryState.ExecutionMutationHead resultHead =
                    new InMemoryState.ExecutionMutationHead(
                            request.completedRevision().id(),
                            request.completedRevision().number(),
                            completed.version(),
                            request.completionEvent().sequence(),
                            request.completionEvent().id());
            InMemoryState.ExecutionMutationLink link =
                    new InMemoryState.ExecutionMutationLink(
                            source.head(),
                            resultHead,
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .stepCompletion(
                                            request.completionEvent().id()));
            InMemoryState.StepCompletionMarker marker =
                    new InMemoryState.StepCompletionMarker(request, result, link);

            NavigableMap<Long, EventEnvelope> committedStream =
                    new TreeMap<>(source.eventStream());
            committedStream.put(request.completionEvent().sequence(),
                    request.completionEvent());
            Map<EventId, InMemoryState.StepCompletionMarker> committedMarkers =
                    new LinkedHashMap<>(state.stepCompletions.get(request.planId()));
            committedMarkers.put(request.completionEvent().id(), marker);
            List<InMemoryState.ExecutionMutationLink> committedLinks =
                    new ArrayList<>(source.links());
            committedLinks.add(link);

            state.plans.put(request.planId(), planValidation.plan());
            state.eventStreams.put(request.planId(), committedStream);
            state.eventsById.put(
                    request.completionEvent().id(), request.completionEvent());
            state.checkpoints.put(request.planId(), completed);
            state.stepCompletions.put(request.planId(), committedMarkers);
            state.executionMutationLinks.put(request.planId(), committedLinks);
            state.executionMutationHeads.put(request.planId(), resultHead);
            return PersistenceResult.applied(result);
        }
    }

    private InMemoryState.StepCompletionMarker completionMarker(
            io.paperagent.v2.contracts.PlanId planId,
            EventId eventId) {
        Map<EventId, InMemoryState.StepCompletionMarker> markers =
                state.stepCompletions.get(planId);
        return markers == null ? null : markers.get(eventId);
    }

    private PersistenceResult<PersistedStepCompletion> validateLiveLease(
            StepCompletionRequest request,
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

    private static PersistenceResult<PersistedStepCompletion> validateExpectedSource(
            StepCompletionRequest request,
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
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            StepCompletionRequest request) {
        PlanRevision latest = source.plan().latestRevision();
        Checkpoint checkpoint = source.checkpoint().checkpoint();
        PlanStep target = latest.steps().stream()
                .filter(step -> step.id().equals(request.stepId()))
                .findFirst()
                .orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || !request.completionFact().stepId().equals(request.stepId())
                || checkpoint.stepStates().get(request.stepId())
                        != StepExecutionState.ACTIVE
                || latest.completedFacts().containsKey(request.stepId())) {
            return false;
        }
        int active = 0;
        for (StepExecutionState state : checkpoint.stepStates().values()) {
            if (state == StepExecutionState.ACTIVE) {
                active++;
            }
            if (state == StepExecutionState.PAUSED
                    || state == StepExecutionState.FAILED
                    || state == StepExecutionState.CANCELLED) {
                return false;
            }
        }
        return active == 1;
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

    private PersistenceResult<PersistedStepCompletion> validateEffectEvidence(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            StepCompletionRequest request) {
        List<EffectReceipt> receipts = new ArrayList<>();
        for (Map.Entry<ToolCallId, InMemoryState.EffectIntentMarker> entry :
                state.effectIntents.entrySet()) {
            ToolCallId toolCallId = entry.getKey();
            InMemoryState.EffectIntentMarker intentMarker = entry.getValue();
            if (toolCallId == null || intentMarker == null
                    || intentMarker.request() == null) {
                return partialState();
            }
            EffectIntent intent = intentMarker.request().intent();
            if (!intent.planId().equals(request.planId())
                    || !intent.stepId().equals(request.stepId())) {
                continue;
            }
            if (!InMemoryEffectIntentRepository
                    .isIntactMarker(toolCallId, intentMarker)) {
                return partialState();
            }
            if (!intentMarker.result().activationEventId().equals(
                    source.head().mutationEventId())) {
                return partialState();
            }
            InMemoryState.EffectResultMarker resultMarker =
                    state.effectResults.get(toolCallId);
            if (resultMarker == null) {
                return notEligibleEvidence();
            }
            if (!InMemoryEffectOutcomeRepository.isIntactResultMarker(
                    state, toolCallId, resultMarker)) {
                return partialState();
            }
            ExecutionReceipt receipt = resultMarker.result().receipt();
            receipts.add(new EffectReceipt(toolCallId, receipt.id()));
        }
        receipts.sort(Comparator.comparing(receipt -> receipt.toolCallId().value()));
        List<ReceiptId> expected = receipts.stream()
                .map(EffectReceipt::receiptId)
                .toList();
        return request.completionFact().receiptReferences().equals(expected)
                ? null
                : notEligibleEvidence();
    }

    private PersistenceResult<PersistedStepCompletion> validateEvent(
            StepCompletionRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        EventEnvelope event = request.completionEvent();
        if (!event.planId().equals(request.planId())) {
            return PersistenceChecks.invalid("request.completionEvent.planId");
        }
        if (!event.taskFrameId().equals(source.taskFrame().id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.completionEvent.taskFrameId");
        }
        if (event.sequence() <= source.eventHeadSequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.completionEvent.sequence");
        }
        return state.eventsById.containsKey(event.id())
                ? PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.completionEvent.id")
                : null;
    }

    private static PlanValidation validateCompletedPlan(
            StepCompletionRequest request,
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        Plan current = source.plan();
        PlanRevision previous = current.latestRevision();
        PlanRevision completed = request.completedRevision();
        Map<PlanStepId, CompletionFact> expectedFacts =
                new LinkedHashMap<>(previous.completedFacts());
        expectedFacts.put(request.stepId(), request.completionFact());
        if (completed.number() != previous.number() + 1
                || !completed.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || completed.createdAt().isBefore(previous.createdAt())
                || !completed.taskFrameId().equals(current.taskFrameId())
                || !completed.steps().equals(previous.steps())
                || !completed.completedFacts().equals(expectedFacts)) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(current.revisions());
        revisions.add(completed);
        try {
            return new PlanValidation(new Plan(
                    current.id(), current.taskFrameId(), revisions));
        } catch (ContractViolationException invalid) {
            return null;
        }
    }

    private static PersistenceResult<PersistedStepCompletion>
            validateCompletedCheckpoint(
                    StepCompletionRequest request,
                    InMemoryExecutionMutationAuthority.AuthoritativeSource source,
                    Plan completedPlan) {
        Checkpoint current = source.checkpoint().checkpoint();
        Checkpoint candidate = request.completedCheckpoint();
        if (candidate.lastEventSequence() != request.completionEvent().sequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.completedCheckpoint.lastEventSequence");
        }
        List<ReceiptId> expectedReceipts = new ArrayList<>(
                current.receiptReferences());
        expectedReceipts.addAll(request.completionFact().receiptReferences());
        boolean allSucceeded = candidate.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);
        if (!candidate.taskFrameId().equals(current.taskFrameId())
                || !candidate.planId().equals(current.planId())
                || !candidate.revisionId().equals(request.completedRevision().id())
                || candidate.revisionNumber() != request.completedRevision().number()
                || candidate.createdAt().isBefore(current.createdAt())
                || !candidate.receiptReferences().equals(expectedReceipts)
                || !candidate.stepStates().keySet().equals(current.stepStates().keySet())
                || !hasOnlyTargetCompletion(current, candidate, request.stepId())
                || candidate.planState() != (allSucceeded
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.ACTIVE)
                || !CheckpointValidators.validate(
                                candidate,
                                source.taskFrame(),
                                completedPlan,
                                current)
                        .isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.completedCheckpoint");
        }
        return null;
    }

    private static boolean hasOnlyTargetCompletion(
            Checkpoint current,
            Checkpoint candidate,
            PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                current.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? StepExecutionState.SUCCEEDED
                    : entry.getValue();
            if (candidate.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static PersistenceResult<PersistedStepCompletion> stale(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedStepCompletion> conflict() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.completionEvent.id");
    }

    private static PersistenceResult<PersistedStepCompletion> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                PARTIAL_PATH);
    }

    private static PersistenceResult<PersistedStepCompletion> notEligibleEvidence() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                EFFECT_OUTCOMES_PATH);
    }

    private record PlanValidation(Plan plan) {
    }

    private record EffectReceipt(ToolCallId toolCallId, ReceiptId receiptId) {
    }
}
