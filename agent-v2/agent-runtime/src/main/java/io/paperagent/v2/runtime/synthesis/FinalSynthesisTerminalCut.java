package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;

/** Immutable successful authority cut required for final synthesis. */
public record FinalSynthesisTerminalCut(
        TaskFrame taskFrame,
        Plan plan,
        Checkpoint checkpoint) {
    public FinalSynthesisTerminalCut {
        if (taskFrame == null || plan == null || checkpoint == null) {
            throw new IllegalArgumentException(
                    "final synthesis terminal cut is invalid");
        }
    }
}
