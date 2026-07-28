package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrameId;
import java.util.List;

public record FinalSynthesisNarrationRequest(
        TaskFrameId taskFrameId,
        PlanId planId,
        PlanRevisionId planRevisionId,
        List<FinalSynthesisReceiptProjection> untrustedReceipts) {
    public FinalSynthesisNarrationRequest {
        if (taskFrameId == null || planId == null || planRevisionId == null
                || untrustedReceipts == null || untrustedReceipts.isEmpty()
                || untrustedReceipts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("final synthesis narration request is invalid");
        }
        untrustedReceipts = List.copyOf(untrustedReceipts);
    }

    @Override
    public String toString() {
        return "FinalSynthesisNarrationRequest[taskFrameId=<provided>, "
                + "planId=<provided>, planRevisionId=<provided>, "
                + "untrustedReceipts=<redacted>]";
    }
}
