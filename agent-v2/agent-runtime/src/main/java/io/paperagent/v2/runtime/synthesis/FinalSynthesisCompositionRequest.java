package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.WorkspaceDiff;
import java.time.Instant;
import java.util.Optional;

public record FinalSynthesisCompositionRequest(
        FinalSynthesisTerminalCut terminalCut,
        Optional<WorkspaceDiff> workspaceDiff,
        Instant observedAt) {
    public FinalSynthesisCompositionRequest {
        if (terminalCut == null || workspaceDiff == null || observedAt == null) {
            throw new IllegalArgumentException("final synthesis request is invalid");
        }
        workspaceDiff = workspaceDiff.map(value -> value);
    }
}
