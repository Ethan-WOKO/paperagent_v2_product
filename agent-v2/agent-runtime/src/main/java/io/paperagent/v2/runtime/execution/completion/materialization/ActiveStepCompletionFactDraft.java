package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.contracts.ReceiptId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ActiveStepCompletionFactDraft(
        String outcomeHash,
        Instant completedAt,
        List<ReceiptId> receiptReferences) {

    public ActiveStepCompletionFactDraft {
        outcomeHash = ActiveStepCompletionMaterializationValues.text(
                outcomeHash, "completionFactDraft.outcomeHash");
        ActiveStepCompletionMaterializationValues.required(
                completedAt, "completionFactDraft.completedAt");
        ActiveStepCompletionMaterializationValues.required(
                receiptReferences,
                "completionFactDraft.receiptReferences");
        Set<ReceiptId> seen = new HashSet<>();
        for (int index = 0; index < receiptReferences.size(); index++) {
            ReceiptId receiptId = receiptReferences.get(index);
            ActiveStepCompletionMaterializationValues.required(
                    receiptId,
                    "completionFactDraft.receiptReferences[" + index + "]");
            if (!seen.add(receiptId)) {
                throw ActiveStepCompletionMaterializationValues.validation(
                        ActiveStepCompletionMaterializationValidationCode
                                .DUPLICATE_RECEIPT_ID,
                        ActiveStepCompletionMaterializationStage
                                .COMPLETION_FACT,
                        "completionFactDraft.receiptReferences");
            }
        }
        receiptReferences = List.copyOf(receiptReferences);
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionFactDraft[outcomeHash=<redacted>, "
                + "completedAt=<provided>, receiptReferences=<redacted>]";
    }
}
