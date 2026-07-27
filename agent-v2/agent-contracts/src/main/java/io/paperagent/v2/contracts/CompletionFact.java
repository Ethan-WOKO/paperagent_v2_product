package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;

public record CompletionFact(
        PlanStepId stepId,
        String outcomeHash,
        Instant completedAt,
        List<ReceiptId> receiptReferences) {

    public CompletionFact {
        Contracts.required(stepId, "completionFact.stepId");
        outcomeHash = Contracts.text(outcomeHash, "completionFact.outcomeHash");
        Contracts.required(completedAt, "completionFact.completedAt");
        receiptReferences = Contracts.list(receiptReferences, "completionFact.receiptReferences");
    }
}
