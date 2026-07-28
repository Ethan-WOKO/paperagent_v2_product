package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class ProductStepInterruptionMarkerReader {
    private final ProductStepInterruptionCodec codec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductActiveStepReplanJpaRepository replans;

    ProductStepInterruptionMarkerReader(
            ProductStepInterruptionCodec codec,
            ProductExecutionStartJpaRepository starts,
            ProductStepActivationJpaRepository activations,
            ProductStepCompletionJpaRepository completions,
            ProductActiveStepReplanJpaRepository replans) {
        this.codec = codec;
        this.starts = starts;
        this.activations = activations;
        this.completions = completions;
        this.replans = replans;
    }

    Marker decode(ProductStepInterruptionEntity row) {
        try {
            ProductStepInterruptionCodec.DecodedRequest decoded =
                    codec.decodeRequest(
                            row.requestFormatVersion(), row.requestSha256(),
                            row.requestJson());
            PersistedStepInterruption result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            ProductStepInterruptionCodec.Candidate request =
                    decoded.candidate();
            VersionedCheckpoint checkpoint =
                    result.interruptedCheckpoint();
            if (!row.interruptionKind().equals(decoded.kind().name())
                    || result.kind() != decoded.kind()
                    || !row.planId().equals(request.planId().value())
                    || !row.planId().equals(result.planId().value())
                    || !row.stepId().equals(request.stepId().value())
                    || !row.stepId().equals(result.stepId().value())
                    || !row.interruptionEventId().equals(
                            request.event().id().value())
                    || !row.interruptionEventId().equals(
                            result.interruptionEvent().id().value())
                    || !row.sourceRevisionId().equals(
                            request.expectedRevisionId().value())
                    || row.sourceRevisionNumber()
                            != request.expectedRevisionNumber()
                    || !row.resultRevisionId().equals(
                            checkpoint.checkpoint().revisionId().value())
                    || row.resultRevisionNumber()
                            != checkpoint.checkpoint().revisionNumber()
                    || row.sourceCheckpointVersion()
                            != request.expectedCheckpointVersion()
                    || row.resultCheckpointVersion() != checkpoint.version()
                    || row.sourceEventSequence()
                            != request.expectedEventHeadSequence()
                    || row.resultEventSequence()
                            != result.interruptionEvent().sequence()
                    || !row.leaseOwnerId().equals(result.leaseOwnerId())
                    || row.fencingToken() != request.fencingToken()
                    || row.fencingToken() != result.fencingToken()
                    || !request.event().equals(
                            result.interruptionEvent())
                    || !request.checkpoint().equals(
                            checkpoint.checkpoint())
                    || row.committedAt() == null) {
                return null;
            }
            return new Marker(
                    decoded.kind(), decoded.request(), result);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    Marker read(
            ProductStepInterruptionEntity row,
            PersistedStepRecoveryActive active) {
        Marker marker = decode(row);
        if (marker == null) {
            return null;
        }
        ProductStepInterruptionCodec.Candidate request =
                ProductStepInterruptionCodec.Candidate.from(
                        marker.kind(), marker.request());
        Checkpoint source = active.checkpoint().checkpoint();
        Checkpoint target =
                marker.result().interruptedCheckpoint().checkpoint();
        long sourceVersion = active.checkpoint().version();
        long sourceSequence = source.lastEventSequence();
        long resultVersion = sourceVersion + 1;
        long resultSequence = sourceSequence + 1;
        boolean valid = starts.findByStartEventId(
                        row.interruptionEventId()).isEmpty()
                && activations.findById(
                        row.interruptionEventId()).isEmpty()
                && completions.findById(
                        row.interruptionEventId()).isEmpty()
                && replans.findBySupersessionEventId(
                        row.interruptionEventId()).isEmpty()
                && replans.findByReplanEventId(
                        row.interruptionEventId()).isEmpty()
                && row.planId().equals(active.plan().id().value())
                && row.stepId().equals(active.activation()
                        .stepId().value())
                && row.sourceRevisionId().equals(
                        source.revisionId().value())
                && row.sourceRevisionNumber() == source.revisionNumber()
                && row.sourceCheckpointVersion() == sourceVersion
                && row.sourceEventSequence() == sourceSequence
                && row.resultCheckpointVersion() == resultVersion
                && row.resultEventSequence() == resultSequence
                && marker.result().interruptedCheckpoint().version()
                        == resultVersion
                && marker.result().interruptionEvent().sequence()
                        == resultSequence
                && marker.result().interruptionEvent().planId()
                        .equals(active.plan().id())
                && marker.result().interruptionEvent().taskFrameId()
                        .equals(active.taskFrame().id())
                && request.expectedRevisionId().equals(source.revisionId())
                && request.expectedRevisionNumber()
                        == source.revisionNumber()
                && request.expectedCheckpointVersion() == sourceVersion
                && request.expectedEventHeadSequence() == sourceSequence
                && request.stepId().equals(active.activation().stepId())
                && target.taskFrameId().equals(source.taskFrameId())
                && target.planId().equals(source.planId())
                && target.revisionId().equals(source.revisionId())
                && target.revisionNumber() == source.revisionNumber()
                && target.lastEventSequence() == resultSequence
                && !target.createdAt().isBefore(source.createdAt())
                && target.receiptReferences().equals(
                        source.receiptReferences())
                && target.stepStates().keySet().equals(
                        source.stepStates().keySet())
                && onlyTargetInterrupted(
                        source, target, request.stepId(), marker.kind())
                && target.planState() == planState(marker.kind())
                && CheckpointValidators.validate(
                        target, active.taskFrame(),
                        active.plan(), source).isEmpty();
        return valid ? marker : null;
    }

    private static boolean onlyTargetInterrupted(
            Checkpoint source, Checkpoint target, PlanStepId targetId,
            StepInterruptionKind kind) {
        StepExecutionState targetState = stepState(kind);
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected =
                    entry.getKey().equals(targetId)
                            ? targetState : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static StepExecutionState stepState(
            StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    private static PlanExecutionState planState(
            StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    record Marker(
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result) {
    }
}
