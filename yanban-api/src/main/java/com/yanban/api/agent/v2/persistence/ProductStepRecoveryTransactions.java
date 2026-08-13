package com.yanban.api.agent.v2.persistence;

import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class ProductStepRecoveryTransactions {
    private static final String RECOVERY = "stepRecovery";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductExecutionStartCodec startCodec;
    private final ProductPlanExecutionContextJpaRepository contexts;
    private final ProductPlanExecutionContextCodec contextCodec;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepInterruptionMarkerReader interruptionMarkers;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductStepCompletionMarkerReader completionMarkers;
    private final ProductActiveStepReplanJpaRepository replans;
    private final ProductActiveStepReplanMarkerReader replanMarkers;
    private final ProductPlanReplanMarkerReader ordinaryReplans;

    ProductStepRecoveryTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec,
            ProductPlanExecutionContextJpaRepository contexts,
            ProductPlanExecutionContextCodec contextCodec,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepInterruptionMarkerReader interruptionMarkers,
            ProductStepCompletionJpaRepository completions,
            ProductStepCompletionMarkerReader completionMarkers,
            ProductActiveStepReplanJpaRepository replans,
            ProductActiveStepReplanMarkerReader replanMarkers,
            ProductPlanReplanMarkerReader ordinaryReplans) {
        this.bootstraps = bootstraps;
        this.bootstrapCodec = bootstrapCodec;
        this.starts = starts;
        this.startCodec = startCodec;
        this.contexts = contexts;
        this.contextCodec = contextCodec;
        this.activations = activations;
        this.activationCodec = activationCodec;
        this.interruptions = interruptions;
        this.interruptionMarkers = interruptionMarkers;
        this.completions = completions;
        this.completionMarkers = completionMarkers;
        this.replans = replans;
        this.replanMarkers = replanMarkers;
        this.ordinaryReplans = ordinaryReplans;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId) {
        return inspect(planId, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    PersistenceResult<StepRecoverySnapshot> inspectWriterAuthority(
            PlanId planId) {
        PersistenceResult<StepRecoverySnapshot> inspected =
                inspect(planId, false);
        return inspected.outcome()
                == io.paperagent.v2.persistence.PersistenceOutcome.FOUND
                && !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryActive)
                ? notEligible() : inspected;
    }

    public PersistenceResult<StepRecoverySnapshot> inspectLocked(PlanId planId) {
        return inspect(planId, true);
    }

    private PersistenceResult<StepRecoverySnapshot> inspect(
            PlanId planId, boolean terminalAware) {
        ProductPlanBootstrapEntity bootstrapRow = bootstraps
                .lockByPlanIdForInspection(planId.value()).orElse(null);
        if (bootstrapRow == null) {
            boolean occupied = starts.existsById(planId.value())
                    || contexts.existsById(planId.value())
                    || !activations.findAllByPlanId(planId.value()).isEmpty()
                    || !interruptions.findAllByPlanId(
                            planId.value()).isEmpty()
                    || !completions.findAllByPlanId(
                            planId.value()).isEmpty()
                    || !replans
                            .findAllByPlanIdOrderBySourceEventSequenceAsc(
                                    planId.value()).isEmpty()
                    || !ordinaryReplans.findAllByPlanId(
                            planId.value()).isEmpty();
            return occupied
                    ? partial()
                    : PersistenceResult.rejected(
                            PersistenceErrorCode.NOT_FOUND, "planId");
        }

        Source source = source(planId, bootstrapRow);
        if (source == null) {
            return partial();
        }
        ContextCut context = context(source);
        if (context == null) {
            return partial();
        }

        List<ProductStepActivationEntity> rows =
                activations.findAllByPlanIdOrderBySourceEventSequenceAsc(
                        planId.value());
        List<ProductStepInterruptionEntity> interruptionRows = terminalAware
                ? interruptions.findAllByPlanId(planId.value()) : List.of();
        List<ProductStepCompletionEntity> completionRows =
                completions.findAllByPlanIdOrderBySourceEventSequenceAsc(
                        planId.value());
        List<ProductActiveStepReplanEntity> replanRows =
                replans.findAllByPlanIdOrderBySourceEventSequenceAsc(
                        planId.value());
        List<ProductPlanReplanMarkerReader.Marker> ordinaryRows;
        try {
            ordinaryRows = ordinaryReplans.findAllByPlanId(planId.value());
        } catch (RuntimeException corrupt) {
            return partial();
        }
        if (rows.isEmpty() && (!interruptionRows.isEmpty()
                || !completionRows.isEmpty() || !replanRows.isEmpty())) {
            return partial();
        }
        Fold fold = fold(
                source, context, rows, completionRows, replanRows,
                ordinaryRows);
        if (fold == null) {
            return partial();
        }
        if (interruptionRows.isEmpty()) {
            return PersistenceResult.found(fold.snapshot());
        }
        if (!terminalAware || interruptionRows.size() != 1
                || !(fold.snapshot() instanceof PersistedStepRecoveryActive
                active)) {
            return partial();
        }
        return interruptionMarkers.read(interruptionRows.get(0), active) == null
                ? partial() : notEligible();
    }

    private Fold fold(
            Source source, ContextCut context,
            List<ProductStepActivationEntity> activationRows,
            List<ProductStepCompletionEntity> completionRows,
            List<ProductActiveStepReplanEntity> replanRows,
            List<ProductPlanReplanMarkerReader.Marker> ordinaryRows) {
        Plan plan = source.bootstrap().plan();
        VersionedCheckpoint head = source.started().startedCheckpoint();
        int activationIndex = 0;
        int completionIndex = 0;
        PersistedStepRecoveryActive active = null;
        int replanIndex = 0;
        int ordinaryIndex = 0;
        while (true) {
            while (ordinaryIndex < ordinaryRows.size()
                    && ordinaryRows.get(ordinaryIndex).sourceEventSequence()
                    == head.checkpoint().lastEventSequence()) {
                if (active != null) {
                    return null;
                }
                OrdinaryFold ordinary = ordinary(
                        source, plan, head, ordinaryRows.get(ordinaryIndex++));
                if (ordinary == null) {
                    return null;
                }
                plan = ordinary.plan();
                head = ordinary.head();
            }
            if (activationIndex >= activationRows.size()) {
                break;
            }
            ProductStepActivationEntity activationRow =
                    activationRows.get(activationIndex++);
            Marker activation = marker(activationRow, source, plan, head);
            if (activation == null) {
                return null;
            }
            active = new PersistedStepRecoveryActive(
                    source.bootstrap().taskFrame(), plan,
                    activation.result().activatedCheckpoint(),
                    activation.result(), context.confirmed());
            head = active.checkpoint();

            if (completionIndex >= completionRows.size()
                    || completionRows.get(completionIndex)
                    .sourceEventSequence()
                    != active.activation().activationEvent().sequence()) {
                ProductActiveStepReplanEntity replanRow =
                        replanIndex < replanRows.size()
                                ? replanRows.get(replanIndex) : null;
                if (replanRow != null
                        && replanRow.sourceEventSequence()
                                == active.checkpoint().checkpoint()
                                        .lastEventSequence()) {
                    ProductActiveStepReplanMarkerReader.Folded
                            replacement =
                            replanMarkers.read(replanRow, active);
                    if (replacement == null) {
                        return null;
                    }
                    plan = replacement.plan();
                    head = replacement.marker().result()
                            .replannedCheckpoint();
                    active = null;
                    replanIndex++;
                    continue;
                }
                if (activationIndex != activationRows.size()
                        || completionIndex != completionRows.size()
                        || replanIndex != replanRows.size()
                        || ordinaryIndex != ordinaryRows.size()) {
                    return null;
                }
                return new Fold(active);
            }
            ProductStepCompletionEntity completionRow =
                    completionRows.get(completionIndex++);
            ProductStepCompletionMarkerReader.Marker completion =
                    completionMarkers.read(completionRow, active);
            if (completion == null) {
                return null;
            }
            List<PlanRevision> revisions =
                    new ArrayList<>(plan.revisions());
            revisions.add(completion.result().completedRevision());
            try {
                plan = new Plan(plan.id(), plan.taskFrameId(), revisions);
            } catch (RuntimeException exception) {
                return null;
            }
            head = completion.result().completedCheckpoint();
            active = null;
        }
        if (completionIndex != completionRows.size() || active != null
                || replanIndex != replanRows.size()
                || ordinaryIndex != ordinaryRows.size()) {
            return null;
        }
        if (head.checkpoint().planState() == PlanExecutionState.SUCCEEDED
                && head.checkpoint().stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED)) {
            return new Fold(new PersistedStepRecoverySucceeded(
                    source.bootstrap().taskFrame(), plan, head,
                    context.confirmed()));
        }
        PlanStepId ready = firstReady(plan, head.checkpoint());
        return ready == null ? null : new Fold(new PersistedStepRecoveryReady(
                source.bootstrap().taskFrame(), plan, head, ready,
                context.confirmed()));
    }

    private static OrdinaryFold ordinary(
            Source source, Plan current, VersionedCheckpoint head,
            ProductPlanReplanMarkerReader.Marker marker) {
        try {
            var request = marker.request();
            var result = marker.result();
            PlanRevision previous = current.latestRevision();
            PlanRevision revision = result.replannedRevision();
            Checkpoint sourceCheckpoint = head.checkpoint();
            Checkpoint target = result.replannedCheckpoint().checkpoint();
            if (!request.planId().equals(current.id())
                    || !result.planId().equals(current.id())
                    || !request.expectedRevisionId().equals(previous.id())
                    || request.expectedRevisionNumber() != previous.number()
                    || request.expectedCheckpointVersion() != head.version()
                    || request.expectedEventHeadSequence()
                            != sourceCheckpoint.lastEventSequence()
                    || !request.replanEvent().equals(result.replanEvent())
                    || !request.replannedRevision().equals(revision)
                    || !request.replannedCheckpoint().equals(target)
                    || revision.number() != previous.number() + 1
                    || !revision.parentRevisionId().equals(
                            Optional.of(previous.id()))
                    || !revision.taskFrameId().equals(current.taskFrameId())
                    || revision.createdAt().isBefore(previous.createdAt())
                    || !revision.completedFacts().equals(
                            previous.completedFacts())
                    || marker.resultEventSequence()
                            != request.replanEvent().sequence()
                    || request.replanEvent().sequence()
                            <= sourceCheckpoint.lastEventSequence()
                    || !request.replanEvent().planId().equals(current.id())
                    || !request.replanEvent().taskFrameId().equals(
                            source.bootstrap().taskFrame().id())
                    || result.replannedCheckpoint().version()
                            != head.version() + 1
                    || !target.taskFrameId().equals(
                            source.bootstrap().taskFrame().id())
                    || !target.planId().equals(current.id())
                    || !target.revisionId().equals(revision.id())
                    || target.revisionNumber() != revision.number()
                    || target.lastEventSequence()
                            != request.replanEvent().sequence()
                    || target.createdAt().isBefore(
                            sourceCheckpoint.createdAt())
                    || target.planState() != PlanExecutionState.ACTIVE
                    || !target.receiptReferences().equals(
                            sourceCheckpoint.receiptReferences())) {
                return null;
            }
            List<PlanRevision> revisions = new ArrayList<>(
                    current.revisions());
            revisions.add(revision);
            Plan replanned = new Plan(
                    current.id(), current.taskFrameId(), revisions);
            Set<PlanStepId> stepIds = revision.steps().stream()
                    .map(PlanStep::id).collect(Collectors.toSet());
            boolean expectedStates = target.stepStates().keySet()
                    .equals(stepIds) && revision.steps().stream().allMatch(
                    step -> target.stepStates().get(step.id())
                            == (revision.completedFacts().containsKey(step.id())
                            ? StepExecutionState.SUCCEEDED
                            : StepExecutionState.NOT_STARTED));
            return expectedStates && CheckpointValidators.validate(
                    target, source.bootstrap().taskFrame(), replanned,
                    sourceCheckpoint).isEmpty()
                    ? new OrdinaryFold(replanned,
                            result.replannedCheckpoint()) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Source source(
            PlanId requested, ProductPlanBootstrapEntity bootstrapRow) {
        ProductExecutionStartEntity startRow =
                starts.findById(requested.value()).orElse(null);
        if (startRow == null) {
            return null;
        }
        try {
            PersistedPlanBootstrap bootstrap = bootstrapCodec.decode(
                    bootstrapRow.payloadFormatVersion(),
                    bootstrapRow.payloadSha256(),
                    bootstrapRow.payloadJson());
            if (!canonicalBootstrap(requested, bootstrapRow, bootstrap)) {
                return null;
            }
            ExecutionStartRequest request = startCodec.decodeRequest(
                    startRow.requestFormatVersion(),
                    startRow.requestSha256(), startRow.requestJson());
            PersistedExecutionStart result = startCodec.decodeResult(
                    startRow.resultFormatVersion(),
                    startRow.resultSha256(), startRow.resultJson());
            return canonicalStart(bootstrap, startRow, request, result)
                    ? new Source(bootstrap, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ContextCut context(Source source) {
        ProductPlanExecutionContextEntity row = contexts.findById(
                source.bootstrap().plan().id().value()).orElse(null);
        if (source.bootstrap().taskFrame().sourceProjectVersion().isEmpty()) {
            return row == null ? new ContextCut(Optional.empty()) : null;
        }
        if (row == null) {
            return null;
        }
        try {
            PlanExecutionContextReservationRequest reservationRequest =
                    contextCodec.decodeReservationRequest(
                            row.reservationRequestFormatVersion(),
                            row.reservationRequestSha256(),
                            row.reservationRequestJson());
            PersistedPlanExecutionContextReserved reservation =
                    contextCodec.decodeReservationResult(
                            row.reservationResultFormatVersion(),
                            row.reservationResultSha256(),
                            row.reservationResultJson());
            if (!canonicalReservation(source, row, reservationRequest,
                    reservation)
                    || row.confirmationLeaseOwnerId() == null
                    || row.confirmationFencingToken() == null
                    || row.confirmationRequestFormatVersion() == null
                    || row.confirmationRequestSha256() == null
                    || row.confirmationRequestJson() == null
                    || row.confirmationResultFormatVersion() == null
                    || row.confirmationResultSha256() == null
                    || row.confirmationResultJson() == null
                    || row.sourceManifestFingerprint() == null) {
                return null;
            }
            PlanExecutionContextConfirmationRequest confirmationRequest =
                    contextCodec.decodeConfirmationRequest(
                            row.confirmationRequestFormatVersion(),
                            row.confirmationRequestSha256(),
                            row.confirmationRequestJson());
            PersistedPlanExecutionContextConfirmed confirmed =
                    contextCodec.decodeConfirmationResult(
                            row.confirmationResultFormatVersion(),
                            row.confirmationResultSha256(),
                            row.confirmationResultJson());
            return canonicalConfirmation(row, reservationRequest, reservation,
                    confirmationRequest, confirmed)
                    ? new ContextCut(Optional.of(confirmed)) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Marker marker(
            ProductStepActivationEntity row, Source source, Plan plan,
            VersionedCheckpoint current) {
        try {
            StepActivationRequest request = activationCodec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedStepActivation result = activationCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            VersionedCheckpoint target = result.activatedCheckpoint();
            Checkpoint h0 = current.checkpoint();
            boolean linked = row.committedAt() != null
                    && starts.findByStartEventId(
                            row.activationEventId()).isEmpty()
                    && interruptions.findById(
                            row.activationEventId()).isEmpty()
                    && completions.findById(
                            row.activationEventId()).isEmpty()
                    && row.planId().equals(
                            source.bootstrap().plan().id().value())
                    && row.planId().equals(request.planId().value())
                    && row.planId().equals(result.planId().value())
                    && row.stepId().equals(request.stepId().value())
                    && row.stepId().equals(result.stepId().value())
                    && row.activationEventId().equals(
                            request.activationEvent().id().value())
                    && row.activationEventId().equals(
                            result.activationEvent().id().value())
                    && row.sourceRevisionId().equals(h0.revisionId().value())
                    && row.sourceRevisionId().equals(
                            request.expectedRevisionId().value())
                    && row.sourceRevisionNumber() == h0.revisionNumber()
                    && row.sourceRevisionNumber()
                            == request.expectedRevisionNumber()
                    && row.resultRevisionId().equals(
                            target.checkpoint().revisionId().value())
                    && row.resultRevisionNumber()
                            == target.checkpoint().revisionNumber()
                    && row.sourceCheckpointVersion() == current.version()
                    && row.sourceCheckpointVersion()
                            == request.expectedCheckpointVersion()
                    && row.resultCheckpointVersion() == target.version()
                    && row.sourceEventSequence()
                            == h0.lastEventSequence()
                    && row.sourceEventSequence()
                            == request.expectedEventHeadSequence()
                    && row.resultEventSequence()
                            == result.activationEvent().sequence()
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == request.fencingToken()
                    && row.fencingToken() == result.fencingToken()
                    && request.activationEvent().equals(
                            result.activationEvent())
                    && request.activatedCheckpoint().equals(
                            target.checkpoint())
                    && request.expectedCheckpointVersion()
                            == current.version()
                    && request.expectedEventHeadSequence()
                            == h0.lastEventSequence()
                    && target.version() == current.version() + 1
                    && result.activationEvent().sequence()
                            == h0.lastEventSequence() + 1
                    && result.activationEvent().planId()
                            .equals(source.bootstrap().plan().id())
                    && result.activationEvent().taskFrameId()
                            .equals(source.bootstrap().taskFrame().id())
                    && target.checkpoint().planId()
                            .equals(source.bootstrap().plan().id())
                    && target.checkpoint().taskFrameId()
                            .equals(source.bootstrap().taskFrame().id())
                    && target.checkpoint().revisionId()
                            .equals(h0.revisionId())
                    && target.checkpoint().revisionNumber()
                            == h0.revisionNumber()
                    && target.checkpoint().lastEventSequence()
                            == h0.lastEventSequence() + 1
                    && target.checkpoint().planState()
                            == PlanExecutionState.ACTIVE
                    && target.checkpoint().receiptReferences().equals(
                            h0.receiptReferences())
                    && target.checkpoint().stepStates().keySet().equals(
                            h0.stepStates().keySet())
                    && h0.stepStates().get(request.stepId())
                            == StepExecutionState.NOT_STARTED
                    && onlyTargetActivated(
                            h0, target.checkpoint(), request.stepId())
                    && CheckpointValidators.validate(
                            target.checkpoint(),
                            source.bootstrap().taskFrame(),
                            plan, h0).isEmpty()
                    && firstReady(plan, h0) != null
                    && firstReady(plan, h0).equals(request.stepId());
            return linked ? new Marker(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean onlyTargetActivated(
            Checkpoint source, Checkpoint target, PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? StepExecutionState.ACTIVE : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean recoverable(Source source, Marker marker) {
        Checkpoint checkpoint =
                marker.result().activatedCheckpoint().checkpoint();
        PlanRevision revision = source.bootstrap().plan().latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || !source.bootstrap().plan().id()
                        .equals(marker.result().planId())) {
            return false;
        }
        PlanStepId active = null;
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : checkpoint.stepStates().entrySet()) {
            if (entry.getValue() == StepExecutionState.ACTIVE) {
                if (active != null) {
                    return false;
                }
                active = entry.getKey();
            } else if (entry.getValue() == StepExecutionState.SUCCEEDED) {
                if (!revision.completedFacts().containsKey(entry.getKey())) {
                    return false;
                }
            } else if (entry.getValue() != StepExecutionState.NOT_STARTED) {
                return false;
            }
        }
        return checkpoint.stepStates().keySet().equals(revision.steps().stream()
                        .map(PlanStep::id).collect(Collectors.toSet()))
                && active != null
                && active.equals(marker.request().stepId())
                && active.equals(marker.result().stepId())
                && revision.completedFacts().entrySet().stream().allMatch(
                        fact -> checkpoint.stepStates().get(fact.getKey())
                                == StepExecutionState.SUCCEEDED);
    }

    private static PlanStepId firstReady(Plan plan, Checkpoint checkpoint) {
        if (checkpoint.planState() != PlanExecutionState.ACTIVE) {
            return null;
        }
        for (PlanStep step : plan.latestRevision().steps()) {
            if (checkpoint.stepStates().get(step.id())
                    != StepExecutionState.NOT_STARTED
                    || plan.latestRevision().completedFacts()
                    .containsKey(step.id())) {
                continue;
            }
            boolean ready = step.dependencies().stream().allMatch(
                    dependency -> checkpoint.stepStates().get(dependency)
                            == StepExecutionState.SUCCEEDED
                            && plan.latestRevision().completedFacts()
                            .containsKey(dependency));
            if (ready) {
                return step.id();
            }
        }
        return null;
    }

    private static boolean canonicalReservation(
            Source source, ProductPlanExecutionContextEntity row,
            PlanExecutionContextReservationRequest request,
            PersistedPlanExecutionContextReserved reserved) {
        Checkpoint h0 = source.started().startedCheckpoint().checkpoint();
        return row.planId().equals(request.planId().value())
                && row.planId().equals(reserved.planId().value())
                && row.workspaceId().equals(
                        request.materializationSpec().workspaceId().value())
                && request.materializationSpec().equals(
                        reserved.materializationSpec())
                && row.reservationLeaseOwnerId().equals(
                        reserved.leaseOwnerId())
                && row.reservationFencingToken() == reserved.fencingToken()
                && request.fencingToken() == reserved.fencingToken()
                && request.expectedRevisionId().equals(h0.revisionId())
                && request.expectedRevisionNumber() == h0.revisionNumber()
                && request.expectedCheckpointVersion()
                        == source.started().startedCheckpoint().version()
                && request.expectedEventHeadSequence()
                        == source.started().startEvent().sequence()
                && source.bootstrap().taskFrame().sourceProjectVersion()
                        .filter(version -> version.equals(
                                reserved.materializationSpec()
                                        .sourceProjectVersion()))
                        .isPresent();
    }

    private static boolean canonicalConfirmation(
            ProductPlanExecutionContextEntity row,
            PlanExecutionContextReservationRequest reservationRequest,
            PersistedPlanExecutionContextReserved reservation,
            PlanExecutionContextConfirmationRequest confirmationRequest,
            PersistedPlanExecutionContextConfirmed confirmed) {
        return confirmationRequest.planId().equals(
                        reservationRequest.planId())
                && confirmationRequest.materializationSpec().equals(
                        reservationRequest.materializationSpec())
                && confirmed.reservation().equals(reservation)
                && row.confirmationLeaseOwnerId().equals(
                        confirmed.leaseOwnerId())
                && row.confirmationFencingToken() == confirmed.fencingToken()
                && confirmationRequest.fencingToken()
                        == confirmed.fencingToken()
                && confirmationRequest.sourceManifestFingerprint().equals(
                        confirmed.sourceManifestFingerprint())
                && row.sourceManifestFingerprint().equals(
                        confirmed.sourceManifestFingerprint().value());
    }

    private static boolean canonicalBootstrap(
            PlanId requested, ProductPlanBootstrapEntity row,
            PersistedPlanBootstrap bootstrap) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        VersionedCheckpoint initial = bootstrap.initialCheckpoint();
        Checkpoint checkpoint = initial.checkpoint();
        PlanRevision revision = plan.latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return row.planId().equals(requested.value())
                && row.planId().equals(plan.id().value())
                && row.taskFrameId().equals(task.id().value())
                && task.id().equals(plan.taskFrameId())
                && initial.version() == 1
                && checkpoint.taskFrameId().equals(task.id())
                && checkpoint.planId().equals(plan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(steps)
                && checkpoint.stepStates().values().stream().allMatch(
                        state -> state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static boolean canonicalStart(
            PersistedPlanBootstrap bootstrap,
            ProductExecutionStartEntity row, ExecutionStartRequest request,
            PersistedExecutionStart result) {
        Checkpoint started = result.startedCheckpoint().checkpoint();
        PlanRevision revision = bootstrap.plan().latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return row.committedAt() != null
                && row.planId().equals(bootstrap.plan().id().value())
                && request.planId().equals(bootstrap.plan().id())
                && result.planId().equals(bootstrap.plan().id())
                && row.startEventId().equals(request.startEvent().id().value())
                && row.startEventId().equals(result.startEvent().id().value())
                && row.leaseOwnerId().equals(result.leaseOwnerId())
                && row.fencingToken() == request.fencingToken()
                && row.fencingToken() == result.fencingToken()
                && request.startEvent().equals(result.startEvent())
                && request.startedCheckpoint().equals(started)
                && result.startedCheckpoint().version() == 2
                && request.startEvent().sequence() == 1
                && request.startEvent().planId()
                        .equals(bootstrap.plan().id())
                && request.startEvent().taskFrameId()
                        .equals(bootstrap.taskFrame().id())
                && started.lastEventSequence() == 1
                && started.planId().equals(bootstrap.plan().id())
                && started.taskFrameId().equals(bootstrap.taskFrame().id())
                && started.revisionId().equals(revision.id())
                && started.revisionNumber() == revision.number()
                && started.planState() == PlanExecutionState.ACTIVE
                && started.stepStates().keySet().equals(steps)
                && started.stepStates().values().stream().allMatch(
                        state -> state == StepExecutionState.NOT_STARTED)
                && started.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static PersistenceResult<StepRecoverySnapshot> partial() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE, RECOVERY);
    }

    private static PersistenceResult<StepRecoverySnapshot> notEligible() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE, RECOVERY);
    }

    private record Source(
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart started) {
    }

    private record ContextCut(
            Optional<PersistedPlanExecutionContextConfirmed> confirmed) {
    }

    private record Marker(
            StepActivationRequest request,
            PersistedStepActivation result) {
    }

    private record Fold(StepRecoverySnapshot snapshot) {
    }

    private record OrdinaryFold(Plan plan, VersionedCheckpoint head) {
    }
}
