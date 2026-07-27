package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductStepCompletionTransactions {
    private static final String PARTIAL = "stepCompletion";
    private static final String EFFECTS = "stepCompletion.effectOutcomes";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductStepCompletionEvidenceJpaRepository evidence;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductLeaseJpaRepository leases;
    private final ProductEffectIntentJpaRepository intents;
    private final ProductEffectOutcomeResultJpaRepository outcomeResults;
    private final ProductEffectOutcomeMarkerReader outcomeMarkers;
    private final ProductStepRecoveryTransactions recovery;
    private final ProductStepCompletionMarkerReader markerReader;
    private final ProductStepCompletionCodec codec;
    private final ProductEffectOutcomeTimeSource time;
    private final EntityManager entityManager;

    ProductStepCompletionTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductStepCompletionJpaRepository completions,
            ProductStepCompletionEvidenceJpaRepository evidence,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductExecutionStartJpaRepository starts,
            ProductLeaseJpaRepository leases,
            ProductEffectIntentJpaRepository intents,
            ProductEffectOutcomeResultJpaRepository outcomeResults,
            ProductEffectOutcomeMarkerReader outcomeMarkers,
            ProductStepRecoveryTransactions recovery,
            ProductStepCompletionMarkerReader markerReader,
            ProductStepCompletionCodec codec,
            ProductEffectOutcomeTimeSource time,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.completions = completions;
        this.evidence = evidence;
        this.interruptions = interruptions;
        this.activations = activations;
        this.activationCodec = activationCodec;
        this.starts = starts;
        this.leases = leases;
        this.intents = intents;
        this.outcomeResults = outcomeResults;
        this.outcomeMarkers = outcomeMarkers;
        this.recovery = recovery;
        this.markerReader = markerReader;
        this.codec = codec;
        this.time = time;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedStepCompletion> complete(
            StepCompletionRequest request) {
        PersistenceResult<PersistedStepCompletion> replay = existing(request);
        if (replay != null) {
            return replay;
        }

        PersistenceResult<io.paperagent.v2.persistence.StepRecoverySnapshot>
                inspected = recovery.inspectWriterAuthority(request.planId());
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryActive active)) {
            return inspected.failure().isPresent()
                    && inspected.failure().orElseThrow().code()
                    == PersistenceErrorCode.NOT_FOUND
                    ? rejected(PersistenceErrorCode.NOT_FOUND, "request.planId")
                    : partial();
        }

        ProductPlanBootstrapEntity locked = bootstraps
                .lockByPlanId(request.planId().value()).orElse(null);
        if (locked == null) {
            return partial();
        }
        replay = existing(request);
        if (replay != null) {
            return replay;
        }
        if (!interruptions.findAllByPlanId(request.planId().value()).isEmpty()
                || !canonicalActivation(active, request)) {
            return partial();
        }

        Instant now = time.observe();
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        request.planId().value()).orElse(null);
        PersistenceResult<PersistedStepCompletion> leaseFailure =
                validateLease(request, lease, now);
        if (leaseFailure != null) {
            return leaseFailure;
        }
        PersistenceResult<PersistedStepCompletion> stale =
                validateExpected(request, active);
        if (stale != null) {
            return stale;
        }
        if (!eligible(request, active)) {
            return rejected(
                    PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                    PARTIAL);
        }

        EvidenceCut cut = evidence(request, active);
        if (cut.failure() != null) {
            return cut.failure();
        }
        PersistenceResult<PersistedStepCompletion> eventFailure =
                validateEvent(request, active);
        if (eventFailure != null) {
            return eventFailure;
        }
        Plan completedPlan = validatePlan(request, active);
        if (completedPlan == null) {
            return rejected(PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                    "request.completedRevision");
        }
        PersistenceResult<PersistedStepCompletion> checkpointFailure =
                validateCheckpoint(request, active, completedPlan);
        if (checkpointFailure != null) {
            return checkpointFailure;
        }

        VersionedCheckpoint checkpoint = new VersionedCheckpoint(
                request.expectedCheckpointVersion() + 1,
                request.completedCheckpoint());
        PersistedStepCompletion result = new PersistedStepCompletion(
                request.planId(), request.stepId(), lease.ownerId(),
                lease.fencingToken(), request.completionEvent(),
                request.completedRevision(), checkpoint);
        ProductStepCompletionCodec.EncodedPayload requestPayload =
                codec.encodeRequest(request);
        ProductStepCompletionCodec.EncodedPayload resultPayload =
                codec.encodeResult(result);
        ProductStepCompletionEntity marker =
                new ProductStepCompletionEntity(
                        request.planId().value(), request.stepId().value(),
                        active.activation().activationEvent().id().value(),
                        request.completionEvent().id().value(),
                        active.plan().latestRevision().id().value(),
                        active.plan().latestRevision().number(),
                        request.completedRevision().id().value(),
                        request.completedRevision().number(),
                        active.checkpoint().version(), checkpoint.version(),
                        active.activation().activationEvent().sequence(),
                        request.completionEvent().sequence(),
                        lease.ownerId(), lease.fencingToken(),
                        requestPayload, resultPayload, now);
        entityManager.persist(marker);
        int ordinal = 0;
        for (EffectReceipt receipt : cut.receipts()) {
            entityManager.persist(new ProductStepCompletionEvidenceEntity(
                    request.completionEvent().id().value(), ordinal++,
                    request.planId().value(), request.stepId().value(),
                    active.activation().activationEvent().id().value(),
                    receipt.toolCallId().value(), receipt.receiptId().value()));
        }
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedStepCompletion> classify(
            StepCompletionRequest request) {
        return existing(request);
    }

    private PersistenceResult<PersistedStepCompletion> existing(
            StepCompletionRequest request) {
        ProductStepCompletionEntity byEvent = completions.findById(
                request.completionEvent().id().value()).orElse(null);
        List<ProductStepCompletionEntity> byPlan =
                completions.findAllByPlanId(request.planId().value());
        if (byEvent == null && byPlan.isEmpty()) {
            return null;
        }
        if (byEvent == null || byPlan.size() != 1
                || byPlan.get(0) != byEvent
                        && !byPlan.get(0).completionEventId()
                        .equals(byEvent.completionEventId())) {
            return partial();
        }
        ProductStepCompletionMarkerReader.Marker marker =
                markerReader.decode(byEvent);
        if (marker == null) {
            return partial();
        }
        return marker.request().equals(request)
                ? PersistenceResult.replayed(marker.result())
                : rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.completionEvent.id");
    }

    private boolean canonicalActivation(
            PersistedStepRecoveryActive active, StepCompletionRequest request) {
        List<ProductStepActivationEntity> rows =
                activations.findAllByPlanId(request.planId().value());
        if (rows.size() != 1) {
            return false;
        }
        ProductStepActivationEntity row = rows.get(0);
        return row.activationEventId().equals(
                        active.activation().activationEvent().id().value())
                && row.planId().equals(request.planId().value())
                && row.stepId().equals(request.stepId().value())
                && active.activation().stepId().equals(request.stepId())
                && active.checkpoint().version() == 3
                && active.activation().activationEvent().sequence() == 2;
    }

    private EvidenceCut evidence(
            StepCompletionRequest request, PersistedStepRecoveryActive active) {
        List<EffectReceipt> receipts = new ArrayList<>();
        for (ProductEffectIntentEntity row :
                intents.findAllByPlanId(request.planId().value())) {
            var intent = outcomeMarkers.intent(row.toolCallId());
            if (intent == null
                    || !row.planId().equals(
                            intent.intent().planId().value())
                    || !row.stepId().equals(
                            intent.intent().stepId().value())
                    || !row.activationEventId().equals(
                            intent.activationEventId().value())) {
                return EvidenceCut.partial();
            }
            if (!intent.intent().stepId().equals(request.stepId())) {
                continue;
            }
            if (!row.activationEventId().equals(
                    active.activation().activationEvent().id().value())
                    || !intent.activationEventId().equals(
                            active.activation().activationEvent().id())) {
                return EvidenceCut.partial();
            }
            ProductEffectOutcomeResultEntity result =
                    outcomeResults.findById(row.toolCallId()).orElse(null);
            if (result == null) {
                return EvidenceCut.notEligible();
            }
            var marker = outcomeMarkers.result(result);
            if (marker == null) {
                return EvidenceCut.partial();
            }
            receipts.add(new EffectReceipt(
                    new ToolCallId(row.toolCallId()),
                    marker.result().receipt().id()));
        }
        receipts.sort(Comparator.comparing(
                value -> value.toolCallId().value()));
        List<ReceiptId> expected = receipts.stream()
                .map(EffectReceipt::receiptId).toList();
        return request.completionFact().receiptReferences().equals(expected)
                ? new EvidenceCut(List.copyOf(receipts), null)
                : EvidenceCut.notEligible();
    }

    private PersistenceResult<PersistedStepCompletion> validateLease(
            StepCompletionRequest request, ProductLeaseEntity lease,
            Instant now) {
        if (lease == null || lease.releasedAt() != null) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.planId");
        }
        if (!lease.leaseToken().equals(request.leaseToken())) {
            return rejected(PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != request.fencingToken()) {
            return rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        return !lease.expiresAt().isAfter(now)
                ? rejected(PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId") : null;
    }

    private static PersistenceResult<PersistedStepCompletion> validateExpected(
            StepCompletionRequest request, PersistedStepRecoveryActive active) {
        PlanRevision revision = active.plan().latestRevision();
        if (!revision.id().equals(request.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (revision.number() != request.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (active.checkpoint().version()
                != request.expectedCheckpointVersion()) {
            return stale("request.expectedCheckpointVersion");
        }
        return active.activation().activationEvent().sequence()
                != request.expectedEventHeadSequence()
                ? stale("request.expectedEventHeadSequence") : null;
    }

    private static boolean eligible(
            StepCompletionRequest request, PersistedStepRecoveryActive active) {
        Checkpoint checkpoint = active.checkpoint().checkpoint();
        PlanRevision revision = active.plan().latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return checkpoint.planState() == PlanExecutionState.ACTIVE
                && steps.contains(request.stepId())
                && request.completionFact().stepId()
                        .equals(request.stepId())
                && checkpoint.stepStates().get(request.stepId())
                        == StepExecutionState.ACTIVE
                && !revision.completedFacts().containsKey(request.stepId())
                && checkpoint.stepStates().values().stream()
                        .filter(value -> value == StepExecutionState.ACTIVE)
                        .count() == 1
                && checkpoint.stepStates().values().stream().noneMatch(
                        value -> value == StepExecutionState.PAUSED
                                || value == StepExecutionState.FAILED
                                || value == StepExecutionState.CANCELLED);
    }

    private PersistenceResult<PersistedStepCompletion> validateEvent(
            StepCompletionRequest request, PersistedStepRecoveryActive active) {
        EventEnvelope event = request.completionEvent();
        if (!event.planId().equals(request.planId())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    "request.completionEvent.planId");
        }
        if (!event.taskFrameId().equals(active.taskFrame().id())) {
            return rejected(PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.completionEvent.taskFrameId");
        }
        if (event.sequence()
                != active.activation().activationEvent().sequence() + 1) {
            return rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.completionEvent.sequence");
        }
        String id = event.id().value();
        return starts.findByStartEventId(id).isPresent()
                || activations.existsById(id)
                || interruptions.existsById(id)
                || completions.existsById(id)
                ? rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.completionEvent.id") : null;
    }

    private static Plan validatePlan(
            StepCompletionRequest request, PersistedStepRecoveryActive active) {
        Plan current = active.plan();
        PlanRevision previous = current.latestRevision();
        PlanRevision completed = request.completedRevision();
        Map<PlanStepId, CompletionFact> facts =
                new LinkedHashMap<>(previous.completedFacts());
        facts.put(request.stepId(), request.completionFact());
        if (completed.number() != previous.number() + 1
                || !completed.parentRevisionId()
                        .equals(java.util.Optional.of(previous.id()))
                || completed.createdAt().isBefore(previous.createdAt())
                || !completed.taskFrameId().equals(current.taskFrameId())
                || !completed.steps().equals(previous.steps())
                || !completed.completedFacts().equals(facts)) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(
                current.revisions());
        revisions.add(completed);
        try {
            return new Plan(current.id(), current.taskFrameId(), revisions);
        } catch (ContractViolationException exception) {
            return null;
        }
    }

    private static PersistenceResult<PersistedStepCompletion>
            validateCheckpoint(
                    StepCompletionRequest request,
                    PersistedStepRecoveryActive active, Plan completedPlan) {
        Checkpoint current = active.checkpoint().checkpoint();
        Checkpoint candidate = request.completedCheckpoint();
        if (candidate.lastEventSequence()
                != request.completionEvent().sequence()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.completedCheckpoint.lastEventSequence");
        }
        List<ReceiptId> receipts =
                new ArrayList<>(current.receiptReferences());
        receipts.addAll(request.completionFact().receiptReferences());
        boolean allSucceeded = candidate.stepStates().values().stream()
                .allMatch(value -> value == StepExecutionState.SUCCEEDED);
        if (!candidate.taskFrameId().equals(current.taskFrameId())
                || !candidate.planId().equals(current.planId())
                || !candidate.revisionId()
                        .equals(request.completedRevision().id())
                || candidate.revisionNumber()
                        != request.completedRevision().number()
                || candidate.createdAt().isBefore(current.createdAt())
                || !candidate.receiptReferences().equals(receipts)
                || !candidate.stepStates().keySet()
                        .equals(current.stepStates().keySet())
                || !onlyTargetCompleted(
                        current, candidate, request.stepId())
                || candidate.planState() != (allSucceeded
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.ACTIVE)
                || !CheckpointValidators.validate(
                        candidate, active.taskFrame(),
                        completedPlan, current).isEmpty()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.completedCheckpoint");
        }
        return null;
    }

    private static boolean onlyTargetCompleted(
            Checkpoint source, Checkpoint target, PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? StepExecutionState.SUCCEEDED : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static PersistenceResult<PersistedStepCompletion> stale(
            String path) {
        return rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedStepCompletion> partial() {
        return rejected(
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE, PARTIAL);
    }

    private static PersistenceResult<PersistedStepCompletion> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record EffectReceipt(
            ToolCallId toolCallId, ReceiptId receiptId) {
    }

    private record EvidenceCut(
            List<EffectReceipt> receipts,
            PersistenceResult<PersistedStepCompletion> failure) {
        static EvidenceCut partial() {
            return new EvidenceCut(List.of(),
                    ProductStepCompletionTransactions.partial());
        }

        static EvidenceCut notEligible() {
            return new EvidenceCut(List.of(), rejected(
                    PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                    EFFECTS));
        }
    }
}
