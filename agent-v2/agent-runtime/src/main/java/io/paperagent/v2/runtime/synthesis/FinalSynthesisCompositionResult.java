package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.FinalSynthesis;

public record FinalSynthesisCompositionResult(
        FinalSynthesis synthesis,
        FinalSynthesisDisposition disposition) {
    public FinalSynthesisCompositionResult {
        if (synthesis == null || disposition == null) {
            throw new IllegalArgumentException("final synthesis result is invalid");
        }
    }
}
