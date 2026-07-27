package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class ProductStepCompletionMarkerReader {
    private final ProductStepCompletionEvidenceJpaRepository evidence;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductEffectIntentJpaRepository intents;
    private final ProductEffectOutcomeResultJpaRepository outcomeResults;
    private final ProductEffectOutcomeMarkerReader outcomeMarkers;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepCompletionCodec codec;

    ProductStepCompletionMarkerReader(
            ProductStepCompletionEvidenceJpaRepository evidence,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductEffectIntentJpaRepository intents,
            ProductEffectOutcomeResultJpaRepository outcomeResults,
            ProductEffectOutcomeMarkerReader outcomeMarkers,
            ProductExecutionStartJpaRepository starts,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepCompletionCodec codec) {
        this.evidence = evidence;
        this.activations = activations;
        this.activationCodec = activationCodec;
        this.intents = intents;
        this.outcomeResults = outcomeResults;
        this.outcomeMarkers = outcomeMarkers;
        this.starts = starts;
        this.interruptions = interruptions;
        this.codec = codec;
    }

    Marker decode(ProductStepCompletionEntity row) {
        try {
            StepCompletionRequest request = codec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedStepCompletion result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            ProductStepActivationEntity activationRow = activations.findById(
                    row.activationEventId()).orElse(null);
            if (activationRow == null) {
                return null;
            }
            var activationRequest = activationCodec.decodeRequest(
                    activationRow.requestFormatVersion(),
                    activationRow.requestSha256(),
                    activationRow.requestJson());
            var activationResult = activationCodec.decodeResult(
                    activationRow.resultFormatVersion(),
                    activationRow.resultSha256(),
                    activationRow.resultJson());
            List<ProductStepCompletionEvidenceEntity> rows = evidence
                    .findAllByCompletionEventIdOrderByOrdinal(
                            row.completionEventId());
            List<EffectReceipt> canonical = new ArrayList<>();
            for (ProductEffectIntentEntity intentRow :
                    intents.findAllByPlanId(row.planId())) {
                PersistedEffectIntent intent =
                        outcomeMarkers.intent(intentRow.toolCallId());
                if (intent == null
                        || !intentRow.planId().equals(
                                intent.intent().planId().value())
                        || !intentRow.stepId().equals(
                                intent.intent().stepId().value())
                        || !intentRow.activationEventId().equals(
                                intent.activationEventId().value())) {
                    return null;
                }
                if (!intent.intent().stepId().value().equals(row.stepId())) {
                    continue;
                }
                ProductEffectOutcomeResultEntity outcome = outcomeResults
                        .findById(intentRow.toolCallId()).orElse(null);
                var outcomeMarker = outcome == null
                        ? null : outcomeMarkers.result(outcome);
                if (outcomeMarker == null
                        || !intentRow.activationEventId().equals(
                                row.activationEventId())
                        || !intent.activationEventId().value().equals(
                                row.activationEventId())) {
                    return null;
                }
                canonical.add(new EffectReceipt(
                        new ToolCallId(intentRow.toolCallId()),
                        outcomeMarker.result().receipt().id()));
            }
            canonical.sort(Comparator.comparing(
                    value -> value.toolCallId().value()));
            if (rows.size() != canonical.size()) {
                return null;
            }
            List<ReceiptId> receipts = new ArrayList<>();
            int ordinal = 0;
            String previousToolCall = null;
            for (ProductStepCompletionEvidenceEntity item : rows) {
                EffectReceipt expected = canonical.get(ordinal);
                ProductEffectOutcomeResultEntity outcome = outcomeResults
                        .findById(item.toolCallId()).orElse(null);
                if (item.ordinal() != ordinal++
                        || previousToolCall != null
                        && previousToolCall.compareTo(item.toolCallId()) >= 0
                        || !item.planId().equals(row.planId())
                        || !item.stepId().equals(row.stepId())
                        || !item.activationEventId().equals(
                                row.activationEventId())
                        || !item.toolCallId().equals(
                                expected.toolCallId().value())
                        || !item.receiptId().equals(
                                expected.receiptId().value())
                        || outcome == null
                        || !item.receiptId().equals(outcome.receiptId())
                        || outcomeMarkers.result(outcome) == null) {
                    return null;
                }
                previousToolCall = item.toolCallId();
                receipts.add(new ReceiptId(item.receiptId()));
            }
            VersionedCheckpoint checkpoint = result.completedCheckpoint();
            boolean valid = row.committedAt() != null
                    && row.planId().equals(request.planId().value())
                    && row.planId().equals(result.planId().value())
                    && row.stepId().equals(request.stepId().value())
                    && row.stepId().equals(result.stepId().value())
                    && activationRow.planId().equals(row.planId())
                    && activationRow.stepId().equals(row.stepId())
                    && activationRow.activationEventId().equals(
                            row.activationEventId())
                    && activationRequest.planId().equals(request.planId())
                    && activationRequest.stepId().equals(request.stepId())
                    && activationRequest.activationEvent().equals(
                            activationResult.activationEvent())
                    && activationRequest.activatedCheckpoint().equals(
                            activationResult.activatedCheckpoint().checkpoint())
                    && activationResult.activationEvent().id().value()
                            .equals(row.activationEventId())
                    && activationResult.planId().equals(request.planId())
                    && activationResult.stepId().equals(request.stepId())
                    && activationResult.activatedCheckpoint().version()
                            == request.expectedCheckpointVersion()
                    && activationResult.activatedCheckpoint().checkpoint()
                            .revisionId().equals(
                                    request.expectedRevisionId())
                    && activationResult.activatedCheckpoint().checkpoint()
                            .revisionNumber()
                            == request.expectedRevisionNumber()
                    && activationResult.activationEvent().sequence()
                            == request.expectedEventHeadSequence()
                    && row.completionEventId().equals(
                            request.completionEvent().id().value())
                    && row.completionEventId().equals(
                            result.completionEvent().id().value())
                    && row.sourceRevisionId().equals(
                            request.expectedRevisionId().value())
                    && row.sourceRevisionNumber()
                            == request.expectedRevisionNumber()
                    && row.resultRevisionId().equals(
                            result.completedRevision().id().value())
                    && row.resultRevisionNumber()
                            == result.completedRevision().number()
                    && row.sourceCheckpointVersion()
                            == request.expectedCheckpointVersion()
                    && row.resultCheckpointVersion() == checkpoint.version()
                    && row.sourceEventSequence()
                            == request.expectedEventHeadSequence()
                    && row.resultEventSequence()
                            == result.completionEvent().sequence()
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == request.fencingToken()
                    && row.fencingToken() == result.fencingToken()
                    && request.completionEvent().equals(
                            result.completionEvent())
                    && request.completedRevision().equals(
                            result.completedRevision())
                    && request.completedCheckpoint().equals(
                            checkpoint.checkpoint())
                    && request.completionFact().receiptReferences()
                            .equals(receipts);
            return valid ? new Marker(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    Marker read(
            ProductStepCompletionEntity row,
            PersistedStepRecoveryActive active) {
        Marker marker = decode(row);
        if (marker == null) {
            return null;
        }
        StepCompletionRequest request = marker.request();
        PersistedStepCompletion result = marker.result();
        Plan completedPlan = completedPlan(request, active);
        boolean valid = completedPlan != null
                && starts.findByStartEventId(
                        row.completionEventId()).isEmpty()
                && activations.findById(
                        row.completionEventId()).isEmpty()
                && interruptions.findById(
                        row.completionEventId()).isEmpty()
                && row.planId().equals(active.plan().id().value())
                && row.stepId().equals(active.activation().stepId().value())
                && row.activationEventId().equals(active.activation()
                        .activationEvent().id().value())
                && request.expectedRevisionId().equals(active.checkpoint()
                        .checkpoint().revisionId())
                && request.expectedRevisionNumber() == active.checkpoint()
                        .checkpoint().revisionNumber()
                && request.expectedCheckpointVersion()
                        == active.checkpoint().version()
                && request.expectedEventHeadSequence()
                        == active.activation().activationEvent().sequence()
                && request.completionEvent().planId()
                        .equals(active.plan().id())
                && request.completionEvent().taskFrameId()
                        .equals(active.taskFrame().id())
                && request.completionEvent().sequence() == 3
                && result.completedCheckpoint().version() == 4
                && canonicalCheckpoint(request, active, completedPlan);
        return valid ? marker : null;
    }

    private static Plan completedPlan(
            StepCompletionRequest request,
            PersistedStepRecoveryActive active) {
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

    private static boolean canonicalCheckpoint(
            StepCompletionRequest request,
            PersistedStepRecoveryActive active, Plan completedPlan) {
        Checkpoint current = active.checkpoint().checkpoint();
        Checkpoint candidate = request.completedCheckpoint();
        List<ReceiptId> receipts =
                new ArrayList<>(current.receiptReferences());
        receipts.addAll(request.completionFact().receiptReferences());
        boolean allSucceeded = candidate.stepStates().values().stream()
                .allMatch(value -> value == StepExecutionState.SUCCEEDED);
        return candidate.lastEventSequence()
                        == request.completionEvent().sequence()
                && candidate.taskFrameId().equals(current.taskFrameId())
                && candidate.planId().equals(current.planId())
                && candidate.revisionId()
                        .equals(request.completedRevision().id())
                && candidate.revisionNumber()
                        == request.completedRevision().number()
                && !candidate.createdAt().isBefore(current.createdAt())
                && candidate.receiptReferences().equals(receipts)
                && candidate.stepStates().keySet()
                        .equals(current.stepStates().keySet())
                && onlyTargetCompleted(
                        current, candidate, request.stepId())
                && candidate.planState() == (allSucceeded
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.ACTIVE)
                && CheckpointValidators.validate(
                        candidate, active.taskFrame(),
                        completedPlan, current).isEmpty();
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

    record Marker(
            StepCompletionRequest request,
            PersistedStepCompletion result) {
    }

    private record EffectReceipt(
            ToolCallId toolCallId, ReceiptId receiptId) {
    }
}
