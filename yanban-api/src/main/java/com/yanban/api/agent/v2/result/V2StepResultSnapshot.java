package com.yanban.api.agent.v2.result;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persisted proposal and, when accepted, immutable result for one Step. */
public record V2StepResultSnapshot(
        String resultId,
        PlanId planId,
        PlanRevisionId planRevisionId,
        PlanStepId stepId,
        EventId activationEventId,
        V2StepResultSource source,
        String proposedText,
        String proposedSha256,
        List<ReceiptId> evidenceReceiptIds,
        V2StepResultStatus status,
        Optional<String> acceptedText,
        Optional<String> acceptedSha256,
        Instant createdAt,
        Instant updatedAt) {

    public V2StepResultSnapshot {
        if (resultId == null || resultId.isBlank()) {
            throw new IllegalArgumentException("resultId is required");
        }
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(planRevisionId, "planRevisionId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(activationEventId, "activationEventId");
        Objects.requireNonNull(source, "source");
        if (proposedText == null || proposedText.isBlank()) {
            throw new IllegalArgumentException("proposedText is required");
        }
        if (proposedSha256 == null || proposedSha256.length() != 64) {
            throw new IllegalArgumentException("proposedSha256 is invalid");
        }
        evidenceReceiptIds = List.copyOf(
                Objects.requireNonNull(
                        evidenceReceiptIds, "evidenceReceiptIds"));
        Objects.requireNonNull(status, "status");
        acceptedText = Objects.requireNonNull(
                acceptedText, "acceptedText");
        acceptedSha256 = Objects.requireNonNull(
                acceptedSha256, "acceptedSha256");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if ((status == V2StepResultStatus.ACCEPTED)
                != (acceptedText.isPresent()
                && acceptedSha256.isPresent())) {
            throw new IllegalArgumentException(
                    "accepted result fields do not match status");
        }
    }

    @Override
    public String toString() {
        return "V2StepResultSnapshot[resultId=<provided>, "
                + "planId=<provided>, stepId=<provided>, source=" + source
                + ", status=" + status + ", text=<redacted>, "
                + "evidenceReceiptIds=<provided>]";
    }
}
