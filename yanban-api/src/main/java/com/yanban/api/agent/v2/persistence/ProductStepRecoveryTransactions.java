package com.yanban.api.agent.v2.persistence;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductStepRecoveryTransactions {
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
            ProductStepCompletionMarkerReader completionMarkers) {
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
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId) {
        return inspect(planId, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    PersistenceResult<StepRecoverySnapshot> inspectWriterAuthority(
            PlanId planId) {
        return inspect(planId, false);
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
                activations.findAllByPlanId(planId.value());
        List<ProductStepInterruptionEntity> interruptionRows = terminalAware
                ? interruptions.findAllByPlanId(planId.value()) : List.of();
        List<ProductStepCompletionEntity> completionRows = terminalAware
                ? completions.findAllByPlanId(planId.value()) : List.of();
        if (rows.isEmpty()) {
            return interruptionRows.isEmpty() && completionRows.isEmpty()
                    ? notEligible() : partial();
        }
        if (rows.size() != 1) {
            return partial();
        }
        Marker marker = marker(rows.get(0), source);
        if (marker == null) {
            return partial();
        }
        if (!recoverable(source, marker)) {
            return notEligible();
        }
        PersistedStepRecoveryActive active = new PersistedStepRecoveryActive(
                source.bootstrap().taskFrame(), source.bootstrap().plan(),
                marker.result().activatedCheckpoint(), marker.result(),
                context.confirmed());
        if (interruptionRows.isEmpty() && completionRows.isEmpty()) {
            return PersistenceResult.found(active);
        }
        if (interruptionRows.size() > 1 || completionRows.size() > 1
                || !interruptionRows.isEmpty()
                && !completionRows.isEmpty()) {
            return partial();
        }
        if (!interruptionRows.isEmpty()) {
            return interruptionMarkers.read(
                    interruptionRows.get(0), active) == null
                    ? partial() : notEligible();
        }
        return completionMarkers.read(completionRows.get(0), active) == null
                ? partial() : notEligible();
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

    private Marker marker(ProductStepActivationEntity row, Source source) {
        try {
            StepActivationRequest request = activationCodec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedStepActivation result = activationCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            VersionedCheckpoint target = result.activatedCheckpoint();
            Checkpoint h0 = source.started().startedCheckpoint().checkpoint();
            boolean linked = row.committedAt() != null
                    && starts.findByStartEventId(
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
                    && row.sourceCheckpointVersion()
                            == source.started().startedCheckpoint().version()
                    && row.sourceCheckpointVersion()
                            == request.expectedCheckpointVersion()
                    && row.resultCheckpointVersion() == target.version()
                    && row.sourceEventSequence()
                            == source.started().startEvent().sequence()
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
                    && request.expectedCheckpointVersion() == 2
                    && request.expectedEventHeadSequence() == 1
                    && target.version() == 3
                    && result.activationEvent().sequence() == 2
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
                    && target.checkpoint().lastEventSequence() == 2
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
                            source.bootstrap().plan(), h0).isEmpty();
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
}
