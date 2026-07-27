package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistenceOutcome;

public record FreshExecutionStarted(
        PersistenceOutcome startOutcome,
        PersistedExecutionStart persistedStart)
        implements FreshExecutionStartOutcome {

    public FreshExecutionStarted {
        FreshExecutionStartValues.required(
                startOutcome,
                "freshExecutionStarted.startOutcome");
        if (startOutcome != PersistenceOutcome.APPLIED
                && startOutcome != PersistenceOutcome.REPLAYED) {
            throw FreshExecutionStartValues.failure(
                    FreshExecutionStartValidationCode.INVALID_OUTCOME_STATE,
                    "freshExecutionStarted.startOutcome",
                    "start outcome must be APPLIED or REPLAYED");
        }
        FreshExecutionStartValues.required(
                persistedStart,
                "freshExecutionStarted.persistedStart");
    }

    @Override
    public String toString() {
        return "FreshExecutionStarted[startOutcome="
                + startOutcome
                + ", persistedStart=<provided>]";
    }
}
