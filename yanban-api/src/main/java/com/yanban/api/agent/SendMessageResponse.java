package com.yanban.api.agent;

import com.yanban.api.agent.sandbox.CandidateArtifactResponse;

import java.util.List;

public record SendMessageResponse(
        boolean success,
        String assistantContent,
        int steps,
        String errorMessage,
        String navigationUrl,
        List<AgentMessageResponse> messages,
        AgentDebugPayload debug,
        List<ProjectEvidenceResponse> projectEvidence,
        CompletionStatus completionStatus,
        AgentStopReason stopReason,
        String outcome,
        CandidateArtifactResponse candidateArtifact,
        String executionOutcome,
        String taskOutcome,
        EvidenceStatus answerStatus,
        FinalSynthesisInput finalSynthesisInput
) {
    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug,
                               List<ProjectEvidenceResponse> projectEvidence, CompletionStatus completionStatus,
                               AgentStopReason stopReason, String outcome, CandidateArtifactResponse candidateArtifact) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, projectEvidence,
                completionStatus, stopReason, outcome, candidateArtifact,
                legacyExecutionOutcome(success, outcome), legacyTaskOutcome(success, outcome),
                completionStatus == CompletionStatus.VERIFIED || completionStatus == null && success
                        ? EvidenceStatus.VERIFIED : EvidenceStatus.UNVERIFIED,
                null);
    }

    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug,
                               List<ProjectEvidenceResponse> projectEvidence, CompletionStatus completionStatus,
                               AgentStopReason stopReason, String outcome) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, projectEvidence,
                completionStatus, stopReason, outcome, null);
    }

    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug,
                               List<ProjectEvidenceResponse> projectEvidence) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, projectEvidence,
                null, null, null, null);
    }

    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, List.of());
    }

    public SendMessageResponse withFinalSynthesisInput(FinalSynthesisInput input) {
        if (input == null) return this;
        return new SendMessageResponse(success, assistantContent, steps, errorMessage, navigationUrl, messages,
                debug, projectEvidence, completionStatus, stopReason, outcome, candidateArtifact,
                input.executionOutcome(), input.taskOutcome(), input.answerStatus(), input);
    }

    private static String legacyExecutionOutcome(boolean success, String outcome) {
        if ("CANCELLED".equals(outcome) || "TIMED_OUT".equals(outcome) || "FAILED".equals(outcome)) return outcome;
        return success ? "NOT_APPLICABLE" : "UNAVAILABLE";
    }

    private static String legacyTaskOutcome(boolean success, String outcome) {
        if ("PARTIAL".equals(outcome) || "INSUFFICIENT_EVIDENCE".equals(outcome)) return "PARTIAL";
        if ("CANCELLED".equals(outcome) || "TIMED_OUT".equals(outcome)) return outcome;
        return success ? "SUCCESS" : "FAILED";
    }
}
