package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.persistence.PersistenceOutcome;

public record FinalSynthesisCompositionResult(
        FinalSynthesis synthesis,
        PersistenceOutcome outcome) {
    public FinalSynthesisCompositionResult {
        if (synthesis == null
                || (outcome != PersistenceOutcome.APPLIED
                && outcome != PersistenceOutcome.REPLAYED)) {
            throw new IllegalArgumentException("final synthesis result is invalid");
        }
    }
}
