package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.contracts.PlanRevisionId;

import java.time.Instant;

public record ActiveStepCompletionRevisionDraft(
        PlanRevisionId id,
        String reason,
        Instant createdAt) {

    public ActiveStepCompletionRevisionDraft {
        ActiveStepCompletionMaterializationValues.required(
                id, "completionRevisionDraft.id");
        reason = ActiveStepCompletionMaterializationValues.text(
                reason, "completionRevisionDraft.reason");
        ActiveStepCompletionMaterializationValues.required(
                createdAt, "completionRevisionDraft.createdAt");
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionRevisionDraft[id=<redacted>, "
                + "reason=<redacted>, createdAt=<provided>]";
    }
}
