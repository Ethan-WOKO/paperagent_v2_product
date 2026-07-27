package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An immutable aggregate recovery checkpoint.
 *
 * <p>{@code lastEventSequence} is a scalar Plan-global cursor. Zero means no
 * real event has been incorporated; a positive value names the last
 * incorporated event in the Plan stream.
 */
public record Checkpoint(
        TaskFrameId taskFrameId,
        PlanId planId,
        PlanRevisionId revisionId,
        long revisionNumber,
        long lastEventSequence,
        PlanExecutionState planState,
        Map<PlanStepId, StepExecutionState> stepStates,
        List<ReceiptId> receiptReferences,
        Instant createdAt) {

    public Checkpoint {
        Contracts.required(taskFrameId, "checkpoint.taskFrameId");
        Contracts.required(planId, "checkpoint.planId");
        Contracts.required(revisionId, "checkpoint.revisionId");
        if (revisionNumber < 1) {
            Contracts.fail(ViolationCode.CHECKPOINT_REVISION_MISMATCH, "checkpoint.revisionNumber",
                    "revision number must be positive");
        }
        if (lastEventSequence < 0) {
            Contracts.fail(ViolationCode.EVENT_SEQUENCE_REGRESSION, "checkpoint.lastEventSequence",
                    "event sequence must not be negative");
        }
        Contracts.required(planState, "checkpoint.planState");
        stepStates = Contracts.map(stepStates, "checkpoint.stepStates");
        receiptReferences = Contracts.list(receiptReferences, "checkpoint.receiptReferences");
        Contracts.unique(receiptReferences, receiptId -> receiptId, "checkpoint.receiptReferences");
        Contracts.required(createdAt, "checkpoint.createdAt");
    }
}
