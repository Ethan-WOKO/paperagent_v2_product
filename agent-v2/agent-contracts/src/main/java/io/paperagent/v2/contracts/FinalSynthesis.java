package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable presentation candidate that binds delivery references without deciding execution or acceptance.
 */
public record FinalSynthesis(
        FinalSynthesisId id,
        TaskFrameId taskFrameId,
        PlanId planId,
        PlanRevisionId planRevisionId,
        Optional<ProjectVersionRef> sourceProjectVersion,
        WorkspaceDiff workspaceDiff,
        List<ReceiptId> receiptIds,
        String narrative,
        Instant observedAt) {

    public FinalSynthesis {
        id = Contracts.required(id, "finalSynthesis.id");
        taskFrameId = Contracts.required(taskFrameId, "finalSynthesis.taskFrameId");
        planId = Contracts.required(planId, "finalSynthesis.planId");
        planRevisionId = Contracts.required(planRevisionId, "finalSynthesis.planRevisionId");
        sourceProjectVersion = Contracts.required(sourceProjectVersion, "finalSynthesis.sourceProjectVersion")
                .map(value -> new ProjectVersionRef(value.projectId(), value.versionId()));
        workspaceDiff = Contracts.required(workspaceDiff, "finalSynthesis.workspaceDiff");
        if (sourceProjectVersion.isPresent()
                && !sourceProjectVersion.get().equals(workspaceDiff.workspace().sourceProjectVersion())) {
            Contracts.fail(
                    ViolationCode.INCONSISTENT_REFERENCE,
                    "finalSynthesis.sourceProjectVersion",
                    "source project version must match workspace diff provenance");
        }
        receiptIds = Contracts.list(receiptIds, "finalSynthesis.receiptIds");
        Contracts.unique(receiptIds, ReceiptId::value, "finalSynthesis.receiptIds");
        narrative = Contracts.text(narrative, "finalSynthesis.narrative");
        observedAt = Contracts.required(observedAt, "finalSynthesis.observedAt");
    }

    @Override
    public String toString() {
        return "FinalSynthesis["
                + "id=<provided>, "
                + "taskFrameId=<provided>, "
                + "planId=<provided>, "
                + "planRevisionId=<provided>, "
                + "sourceProjectVersion=<provided>, "
                + "workspaceDiff=<provided>, "
                + "receiptIds=<provided>, "
                + "narrative=<provided>, "
                + "observedAt=<provided>]";
    }
}
