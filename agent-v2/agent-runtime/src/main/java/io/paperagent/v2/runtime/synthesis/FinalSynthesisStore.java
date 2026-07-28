package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.PlanId;

import java.util.Optional;

/** Runtime-facing storage boundary for durable final synthesis. */
public interface FinalSynthesisStore {
    Optional<FinalSynthesis> find(PlanId planId);

    Optional<FinalSynthesisCompositionResult> append(FinalSynthesis synthesis);
}
