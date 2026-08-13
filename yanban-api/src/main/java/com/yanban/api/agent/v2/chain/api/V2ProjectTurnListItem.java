package com.yanban.api.agent.v2.chain.api;

import java.time.Instant;
import java.util.List;

public record V2ProjectTurnListItem(
        String clientRequestId,
        String question,
        Instant createdAt,
        Instant updatedAt,
        String workState,
        String taskOutcomeStatus,
        String deliveryStatus,
        String route,
        String planId,
        String baseProjectVersion,
        String publishedProjectVersion,
        Long revisionId,
        String publishReceiptId,
        List<V2ProjectTurnResponse.Step> steps,
        V2ProjectTurnResponse.PendingItem pendingItem,
        V2ProjectTurnResponse.Validation validation,
        String finalText,
        Long candidateArtifactId,
        List<String> outputPaths,
        String failureCategory,
        String failureCode,
        String deliveryErrorCode) {

    public V2ProjectTurnListItem {
        steps = steps == null ? List.of() : List.copyOf(steps);
        outputPaths = outputPaths == null ? List.of() : List.copyOf(outputPaths);
    }

    static V2ProjectTurnListItem from(
            V2ProjectTurnResponse value, String question,
            Instant createdAt, Instant updatedAt) {
        return new V2ProjectTurnListItem(
                value.clientRequestId(), question, createdAt, updatedAt,
                value.workState(), value.taskOutcomeStatus(),
                value.deliveryStatus(), value.route(), value.planId(),
                value.baseProjectVersion(), value.publishedProjectVersion(),
                value.revisionId(), value.publishReceiptId(), value.steps(),
                value.pendingItem(), value.validation(), value.finalText(),
                value.candidateArtifactId(), value.outputPaths(),
                value.failureCategory(), value.failureCode(),
                value.deliveryErrorCode());
    }
}
