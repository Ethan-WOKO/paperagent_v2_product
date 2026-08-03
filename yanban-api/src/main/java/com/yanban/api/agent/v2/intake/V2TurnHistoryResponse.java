package com.yanban.api.agent.v2.intake;

import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnResponse;
import java.time.Instant;
import java.util.List;

public record V2TurnHistoryResponse(
        String clientRequestId,
        String question,
        String status,
        String route,
        String planId,
        String projectVersion,
        List<V2AdaptiveTurnResponse.Step> steps,
        String finalText,
        Long candidateArtifactId,
        List<String> outputPaths,
        String errorCode,
        Instant createdAt,
        Instant updatedAt,
        AgentAutomaticValidation agentAutomaticValidation,
        ConfirmationValidation confirmationValidation,
        V2AdaptiveTurnResponse.Context context) {

    public V2TurnHistoryResponse(
            String clientRequestId, String question, String status,
            String route, String planId, String projectVersion,
            List<V2AdaptiveTurnResponse.Step> steps, String finalText,
            Long candidateArtifactId, List<String> outputPaths,
            String errorCode, Instant createdAt, Instant updatedAt,
            AgentAutomaticValidation agentAutomaticValidation,
            ConfirmationValidation confirmationValidation) {
        this(clientRequestId, question, status, route, planId,
                projectVersion, steps, finalText, candidateArtifactId,
                outputPaths, errorCode, createdAt, updatedAt,
                agentAutomaticValidation, confirmationValidation, null);
    }

    public V2TurnHistoryResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        outputPaths = outputPaths == null
                ? List.of() : List.copyOf(outputPaths);
    }

    public record AgentAutomaticValidation(
            String status,
            String provider,
            int exitCode,
            String receiptId) {
    }

    public record ConfirmationValidation(
            String status,
            String decisionStatus,
            Long applicationOperationId,
            Long appliedRevisionId,
            String appliedProjectVersion) {
    }
}
