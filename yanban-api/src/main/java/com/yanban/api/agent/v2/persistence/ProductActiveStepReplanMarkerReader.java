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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class ProductActiveStepReplanMarkerReader {
    private final ProductActiveStepReplanCodec codec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepCompletionJpaRepository completions;

    ProductActiveStepReplanMarkerReader(
            ProductActiveStepReplanCodec codec,
            ProductExecutionStartJpaRepository starts,
            ProductStepActivationJpaRepository activations,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepCompletionJpaRepository completions) {
        this.codec = codec;
        this.starts = starts;
        this.activations = activations;
        this.interruptions = interruptions;
        this.completions = completions;
    }

    Marker read(ProductActiveStepReplanEntity row) {
        if (row == null) {
            return null;
        }
        try {
            ActiveStepReplanRequest request = codec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedActiveStepReplan result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            boolean valid = row.committedAt() != null
                    && row.planId().equals(request.planId().value())
                    && row.planId().equals(result.planId().value())
                    && row.supersededStepId().equals(
                            request.activeStepId().value())
                    && row.supersededStepId().equals(
                            result.supersededStepId().value())
                    && row.supersessionEventId().equals(
                            request.supersessionEvent().id().value())
                    && row.supersessionEventId().equals(
                            result.supersessionEvent().id().value())
                    && row.replanEventId().equals(
                            request.replanEvent().id().value())
                    && row.replanEventId().equals(
                            result.replanEvent().id().value())
                    && row.sourceRevisionId().equals(
                            request.expectedRevisionId().value())
                    && row.sourceRevisionNumber()
                            == request.expectedRevisionNumber()
                    && row.resultRevisionId().equals(
                            result.replannedRevision().id().value())
                    && row.resultRevisionNumber()
                            == result.replannedRevision().number()
                    && row.sourceCheckpointVersion()
                            == request.expectedCheckpointVersion()
                    && row.supersededCheckpointVersion()
                            == result.supersededCheckpoint().version()
                    && row.resultCheckpointVersion()
                            == result.replannedCheckpoint().version()
                    && row.sourceEventSequence()
                            == request.expectedEventHeadSequence()
                    && row.supersessionEventSequence()
                            == result.supersessionEvent().sequence()
                    && row.resultEventSequence()
                            == result.replanEvent().sequence()
                    && row.leaseOwnerId().equals(
                            result.leaseOwnerId())
                    && row.fencingToken() == request.fencingToken()
                    && row.fencingToken() == result.fencingToken()
                    && request.supersessionEvent().equals(
                            result.supersessionEvent())
                    && request.supersededCheckpoint().equals(
                            result.supersededCheckpoint().checkpoint())
                    && request.replanEvent().equals(
                            result.replanEvent())
                    && request.replannedRevision().equals(
                            result.replannedRevision())
                    && request.replannedCheckpoint().equals(
                            result.replannedCheckpoint().checkpoint());
            return valid ? new Marker(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    Folded read(
            ProductActiveStepReplanEntity row,
            PersistedStepRecoveryActive active) {
        Marker marker = read(row);
        if (marker == null) {
            return null;
        }
        ActiveStepReplanRequest request = marker.request();
        PersistedActiveStepReplan result = marker.result();
        Checkpoint source = active.checkpoint().checkpoint();
        Checkpoint superseded =
                result.supersededCheckpoint().checkpoint();
        Checkpoint replacement =
                result.replannedCheckpoint().checkpoint();
        Plan plan = replacementPlan(active.plan(),
                result.replannedRevision());
        if (plan == null) {
            return null;
        }
        boolean valid = row.planId().equals(active.planId().value())
                && eventIsExclusive(row.supersessionEventId())
                && eventIsExclusive(row.replanEventId())
                && request.planId().equals(active.planId())
                && request.activeStepId().equals(
                        active.activation().stepId())
                && request.expectedRevisionId().equals(
                        source.revisionId())
                && request.expectedRevisionNumber()
                        == source.revisionNumber()
                && request.expectedCheckpointVersion()
                        == active.checkpoint().version()
                && request.expectedEventHeadSequence()
                        == source.lastEventSequence()
                && request.fencingToken()
                        == active.activation().fencingToken()
                && result.leaseOwnerId().equals(
                        active.activation().leaseOwnerId())
                && result.fencingToken()
                        == active.activation().fencingToken()
                && result.supersededCheckpoint().version()
                        == active.checkpoint().version() + 1
                && result.replannedCheckpoint().version()
                        == active.checkpoint().version() + 2
                && result.supersessionEvent().sequence()
                        == source.lastEventSequence() + 1
                && result.replanEvent().sequence()
                        == result.supersessionEvent().sequence() + 1
                && result.supersessionEvent().planId()
                        .equals(active.planId())
                && result.replanEvent().planId()
                        .equals(active.planId())
                && result.supersessionEvent().taskFrameId()
                        .equals(active.taskFrame().id())
                && result.replanEvent().taskFrameId()
                        .equals(active.taskFrame().id())
                && superseded.planState()
                        == PlanExecutionState.ACTIVE
                && superseded.lastEventSequence()
                        == result.supersessionEvent().sequence()
                && superseded.revisionId().equals(
                        source.revisionId())
                && superseded.revisionNumber()
                        == source.revisionNumber()
                && onlySuperseded(source, superseded,
                        request.activeStepId())
                && CheckpointValidators.validate(
                        superseded, active.taskFrame(),
                        active.plan(), source).isEmpty()
                && replacement.planState()
                        == PlanExecutionState.ACTIVE
                && replacement.lastEventSequence()
                        == result.replanEvent().sequence()
                && replacement.revisionId().equals(
                        result.replannedRevision().id())
                && replacement.revisionNumber()
                        == result.replannedRevision().number()
                && replacement.receiptReferences().equals(
                        superseded.receiptReferences())
                && replacementStates(
                        replacement, result.replannedRevision())
                && CheckpointValidators.validate(
                        replacement, active.taskFrame(),
                        plan, superseded).isEmpty();
        return valid ? new Folded(marker, plan) : null;
    }

    private boolean eventIsExclusive(String eventId) {
        return starts.findByStartEventId(eventId).isEmpty()
                && activations.findById(eventId).isEmpty()
                && interruptions.findById(eventId).isEmpty()
                && completions.findById(eventId).isEmpty();
    }

    private static Plan replacementPlan(
            Plan current, PlanRevision replacement) {
        PlanRevision previous = current.latestRevision();
        if (replacement.number() != previous.number() + 1
                || !replacement.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || replacement.createdAt().isBefore(
                        previous.createdAt())
                || !replacement.taskFrameId().equals(
                        current.taskFrameId())
                || !replacement.completedFacts().equals(
                        previous.completedFacts())) {
            return null;
        }
        ArrayList<PlanRevision> revisions =
                new ArrayList<>(current.revisions());
        revisions.add(replacement);
        try {
            return new Plan(
                    current.id(), current.taskFrameId(), revisions);
        } catch (ContractViolationException exception) {
            return null;
        }
    }

    private static boolean onlySuperseded(
            Checkpoint source, Checkpoint target,
            PlanStepId activeStep) {
        if (!target.stepStates().keySet().equals(
                source.stepStates().keySet())
                || !target.receiptReferences().equals(
                        source.receiptReferences())) {
            return false;
        }
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected =
                    entry.getKey().equals(activeStep)
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

    record Marker(
            ActiveStepReplanRequest request,
            PersistedActiveStepReplan result) {
    }

    record Folded(Marker marker, Plan plan) {
    }
}
