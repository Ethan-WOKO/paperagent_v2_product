package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import java.time.Instant;
import java.util.Optional;

public record FinalSynthesisCompositionRequest(
        PersistedStepRecoverySucceeded terminalCut,
        Optional<WorkspaceDiff> workspaceDiff,
        Instant observedAt) {
    public FinalSynthesisCompositionRequest {
        if (terminalCut == null || workspaceDiff == null || observedAt == null) {
            throw new IllegalArgumentException("final synthesis request is invalid");
        }
        workspaceDiff = workspaceDiff.map(value -> value);
    }
}
