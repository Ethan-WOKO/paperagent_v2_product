package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductActiveStepReplanTransactions {
    private static final String ROOT = "activeStepReplan";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductActiveStepReplanJpaRepository replans;
    private final ProductActiveStepReplanMarkerReader markers;
    private final ProductActiveStepReplanCodec codec;
    private final ProductStepRecoveryTransactions recovery;
    private final ProductLeaseJpaRepository leases;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductEffectIntentJpaRepository effectIntents;
    private final ProductActiveStepReplanTimeSource time;

    ProductActiveStepReplanTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductActiveStepReplanJpaRepository replans,
            ProductActiveStepReplanMarkerReader markers,
            ProductActiveStepReplanCodec codec,
            ProductStepRecoveryTransactions recovery,
            ProductLeaseJpaRepository leases,
            ProductExecutionStartJpaRepository starts,
            ProductStepActivationJpaRepository activations,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepCompletionJpaRepository completions,
            ProductEffectIntentJpaRepository effectIntents,
            ProductActiveStepReplanTimeSource time) {
        this.bootstraps = bootstraps;
        this.replans = replans;
        this.markers = markers;
        this.codec = codec;
        this.recovery = recovery;
        this.leases = leases;
        this.starts = starts;
        this.activations = activations;
        this.interruptions = interruptions;
        this.completions = completions;
        this.effectIntents = effectIntents;
        this.time = time;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedActiveStepReplan>
            supersedeAndReplan(ActiveStepReplanRequest request) {
        ProductPlanBootstrapEntity bootstrap = bootstraps.lockByPlanId(
                request.planId().value()).orElse(null);
        ProductActiveStepReplanEntity existing =
                existingFor(request);
        if (existing != null) {
            return replay(existing, request);
        }
        if (bootstrap == null) {
            return occupied(request)
                    ? partial()
                    : rejected(PersistenceErrorCode.NOT_FOUND,
                            "request.planId");
        }
        if (eventOccupied(request.supersessionEvent().id().value())) {
            return conflict("request.supersessionEvent.id");
        }
        if (eventOccupied(request.replanEvent().id().value())) {
            return conflict("request.replanEvent.id");
        }

        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectLocked(request.planId());
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryActive active)) {
            return inspected.outcome() == PersistenceOutcome.REJECTED
                    && inspected.failure().isPresent()
                    ? notEligible() : partial();
        }
        if (effectIntents.findAllByPlanId(request.planId().value()).stream()
                .anyMatch(row -> row.stepId().equals(
                                active.activation().stepId().value())
                        && row.activationEventId().equals(
                                active.activation().activationEvent()
                                        .id().value()))) {
            return notEligible();
        }
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        request.planId().value())
                .orElse(null);
        Instant now = time.now().truncatedTo(ChronoUnit.MICROS);
        PersistenceResult<PersistedActiveStepReplan> authority =
                validateAuthority(request, active, lease, now);
        if (authority != null) {
            return authority;
        }
        Plan replanned = replannedPlan(request, active);
        if (replanned == null) {
            return rejected(
                    PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                    "request.replannedRevision");
        }
        PersistenceResult<PersistedActiveStepReplan> shape =
                validateShape(request, active, replanned);
        if (shape != null) {
            return shape;
        }

        PersistedActiveStepReplan result =
                new PersistedActiveStepReplan(
                        request.planId(),
                        request.activeStepId(),
                        lease.ownerId(),
                        lease.fencingToken(),
                        request.supersessionEvent(),
                        new VersionedCheckpoint(
                                request.expectedCheckpointVersion() + 1,
                                request.supersededCheckpoint()),
                        request.replanEvent(),
                        request.replannedRevision(),
                        new VersionedCheckpoint(
                                request.expectedCheckpointVersion() + 2,
                                request.replannedCheckpoint()));
        ProductActiveStepReplanEntity row =
                new ProductActiveStepReplanEntity(
                        request.planId().value(),
                        request.activeStepId().value(),
                        request.supersessionEvent().id().value(),
                        request.replanEvent().id().value(),
                        request.expectedRevisionId().value(),
                        request.expectedRevisionNumber(),
                        request.replannedRevision().id().value(),
                        request.replannedRevision().number(),
                        request.expectedCheckpointVersion(),
                        result.supersededCheckpoint().version(),
                        result.replannedCheckpoint().version(),
                        request.expectedEventHeadSequence(),
                        request.supersessionEvent().sequence(),
                        request.replanEvent().sequence(),
                        lease.ownerId(), lease.fencingToken(),
                        codec.encodeRequest(request),
                        codec.encodeResult(result), now);
        replans.saveAndFlush(row);
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedActiveStepReplan> classify(
            ActiveStepReplanRequest request) {
        ProductActiveStepReplanEntity row = existingFor(request);
        if (row != null) {
            return replay(row, request);
        }
        return eventOccupied(request.supersessionEvent().id().value())
                ? conflict("request.supersessionEvent.id")
                : conflict("request.replanEvent.id");
    }

    private ProductActiveStepReplanEntity existingFor(
            ActiveStepReplanRequest request) {
        ProductActiveStepReplanEntity bySupersession = replans
                .findBySupersessionEventId(
                        request.supersessionEvent().id().value())
                .orElse(null);
        ProductActiveStepReplanEntity byReplan = replans
                .findByReplanEventId(
                        request.replanEvent().id().value())
                .orElse(null);
        return bySupersession != null ? bySupersession : byReplan;
    }

    private PersistenceResult<PersistedActiveStepReplan> replay(
            ProductActiveStepReplanEntity row,
            ActiveStepReplanRequest request) {
        ProductActiveStepReplanMarkerReader.Marker marker =
                markers.read(row);
        if (marker == null) {
            return partial();
        }
        return marker.request().equals(request)
                ? PersistenceResult.replayed(marker.result())
                : conflict(row.supersessionEventId().equals(
                        request.supersessionEvent().id().value())
                        ? "request.replanEvent.id"
                        : "request.supersessionEvent.id");
    }

    private PersistenceResult<PersistedActiveStepReplan>
            validateAuthority(
                    ActiveStepReplanRequest request,
                    PersistedStepRecoveryActive active,
                    ProductLeaseEntity lease,
                    Instant now) {
        Checkpoint head = active.checkpoint().checkpoint();
        if (!active.planId().equals(request.planId())
                || !active.activation().stepId().equals(
                        request.activeStepId())
                || head.stepStates().get(request.activeStepId())
                        != StepExecutionState.ACTIVE) {
            return notEligible();
        }
        if (lease == null) {
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
        if (lease.releasedAt() != null
                || !lease.expiresAt().isAfter(now)) {
            return rejected(PersistenceErrorCode.LEASE_EXPIRED,
                    "request.planId");
        }
        if (!active.activation().leaseOwnerId().equals(lease.ownerId())
                || active.activation().fencingToken()
                        != lease.fencingToken()) {
            return rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        PlanRevision latest = active.plan().latestRevision();
        if (!latest.id().equals(request.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (latest.number() != request.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (active.checkpoint().version()
                != request.expectedCheckpointVersion()) {
            return stale("request.expectedCheckpointVersion");
        }
        if (head.lastEventSequence()
                != request.expectedEventHeadSequence()) {
            return stale("request.expectedEventHeadSequence");
        }
        return null;
    }

    private static Plan replannedPlan(
            ActiveStepReplanRequest request,
            PersistedStepRecoveryActive active) {
        Plan current = active.plan();
        PlanRevision previous = current.latestRevision();
        PlanRevision next = request.replannedRevision();
        if (next.number() != previous.number() + 1
                || !next.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || next.createdAt().isBefore(previous.createdAt())
                || !next.taskFrameId().equals(current.taskFrameId())
                || !next.completedFacts().equals(
                        previous.completedFacts())
                || next.steps().stream().anyMatch(step ->
                        step.id().equals(request.activeStepId()))) {
            return null;
        }
        ArrayList<PlanRevision> revisions =
                new ArrayList<>(current.revisions());
        revisions.add(next);
        try {
            return new Plan(
                    current.id(), current.taskFrameId(), revisions);
        } catch (ContractViolationException exception) {
            return null;
        }
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            validateShape(
                    ActiveStepReplanRequest request,
                    PersistedStepRecoveryActive active,
                    Plan replanned) {
        Checkpoint source = active.checkpoint().checkpoint();
        if (request.supersessionEvent().id().equals(
                request.replanEvent().id())
                || !request.supersessionEvent().planId().equals(
                        request.planId())
                || !request.replanEvent().planId().equals(
                        request.planId())
                || !request.supersessionEvent().taskFrameId().equals(
                        active.taskFrame().id())
                || !request.replanEvent().taskFrameId().equals(
                        active.taskFrame().id())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    "request.events");
        }
        if (request.supersessionEvent().sequence()
                        != source.lastEventSequence() + 1
                || request.replanEvent().sequence()
                        != request.supersessionEvent().sequence() + 1) {
            return rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.events.sequence");
        }
        if (request.supersessionEvent().occurredAt().isBefore(
                active.activation().activationEvent().occurredAt())
                || request.replanEvent().occurredAt().isBefore(
                        request.supersessionEvent().occurredAt())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    "request.events.occurredAt");
        }
        Checkpoint superseded = request.supersededCheckpoint();
        if (superseded.lastEventSequence()
                        != request.supersessionEvent().sequence()
                || !superseded.taskFrameId().equals(
                        source.taskFrameId())
                || !superseded.planId().equals(source.planId())
                || !superseded.revisionId().equals(
                        source.revisionId())
                || superseded.revisionNumber()
                        != source.revisionNumber()
                || superseded.createdAt().isBefore(
                        source.createdAt())
                || superseded.planState()
                        != PlanExecutionState.ACTIVE
                || !superseded.receiptReferences().equals(
                        source.receiptReferences())
                || !superseded.stepStates().keySet().equals(
                        source.stepStates().keySet())
                || !onlySuperseded(
                        source, superseded,
                        request.activeStepId())
                || !CheckpointValidators.validate(
                        superseded, active.taskFrame(),
                        active.plan(), source).isEmpty()) {
            return checkpoint("request.supersededCheckpoint");
        }
        Checkpoint target = request.replannedCheckpoint();
        PlanRevision revision = request.replannedRevision();
        if (target.lastEventSequence()
                        != request.replanEvent().sequence()
                || !target.taskFrameId().equals(
                        superseded.taskFrameId())
                || !target.planId().equals(
                        superseded.planId())
                || !target.revisionId().equals(revision.id())
                || target.revisionNumber() != revision.number()
                || target.createdAt().isBefore(
                        superseded.createdAt())
                || target.planState() != PlanExecutionState.ACTIVE
                || !target.receiptReferences().equals(
                        superseded.receiptReferences())
                || !replacementStates(target, revision)
                || !CheckpointValidators.validate(
                        target, active.taskFrame(), replanned,
                        superseded).isEmpty()) {
            return checkpoint("request.replannedCheckpoint");
        }
        return null;
    }

    private static boolean onlySuperseded(
            Checkpoint source, Checkpoint target,
            PlanStepId activeStepId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected =
                    entry.getKey().equals(activeStepId)
                            ? StepExecutionState.SUPERSEDED_BY_REPLAN
                            : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean replacementStates(
            Checkpoint checkpoint, PlanRevision revision) {
        Set<PlanStepId> ids = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return checkpoint.stepStates().keySet().equals(ids)
                && revision.steps().stream().allMatch(step ->
                        checkpoint.stepStates().get(step.id())
                                == (revision.completedFacts()
                                        .containsKey(step.id())
                                ? StepExecutionState.SUCCEEDED
                                : StepExecutionState.NOT_STARTED));
    }

    private boolean eventOccupied(String eventId) {
        return starts.findByStartEventId(eventId).isPresent()
                || activations.findById(eventId).isPresent()
                || interruptions.findById(eventId).isPresent()
                || completions.findById(eventId).isPresent()
                || replans.findBySupersessionEventId(eventId)
                        .isPresent()
                || replans.findByReplanEventId(eventId).isPresent();
    }

    private boolean occupied(ActiveStepReplanRequest request) {
        return starts.existsById(request.planId().value())
                || !activations.findAllByPlanId(
                        request.planId().value()).isEmpty()
                || !interruptions.findAllByPlanId(
                        request.planId().value()).isEmpty()
                || !completions.findAllByPlanId(
                        request.planId().value()).isEmpty()
                || !replans.findAllByPlanIdOrderBySourceEventSequenceAsc(
                        request.planId().value()).isEmpty();
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            rejected(PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            stale(String path) {
        return rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            checkpoint(String path) {
        return rejected(
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                path);
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            notEligible() {
        return rejected(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                ROOT + ".source");
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            conflict(String path) {
        return rejected(
                PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static PersistenceResult<PersistedActiveStepReplan>
            partial() {
        return rejected(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_PARTIAL_STATE,
                ROOT);
    }
}
