package com.yanban.api.agent;

import java.util.List;

/** Migration-free, minimal input contract reserved for the later final-synthesis worker. */
public record FinalSynthesisInput(
        String executionOutcome,
        String taskOutcome,
        EvidenceStatus answerStatus,
        List<SynthesisEvidence> evidence,
        VerificationScope verificationScope
) {
    public FinalSynthesisInput {
        executionOutcome = executionOutcome == null ? "UNAVAILABLE" : executionOutcome;
        taskOutcome = taskOutcome == null ? "FAILED" : taskOutcome;
        answerStatus = answerStatus == null ? EvidenceStatus.UNVERIFIED : answerStatus;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        verificationScope = verificationScope == null ? VerificationScope.standard() : verificationScope;
    }
}
