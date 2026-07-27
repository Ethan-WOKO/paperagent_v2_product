package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.persistence.PersistenceFailure;

public record FreshExecutionBootstrapRejected(PersistenceFailure failure)
        implements FreshExecutionStartOutcome {

    public FreshExecutionBootstrapRejected {
        FreshExecutionStartValues.required(
                failure,
                "freshExecutionBootstrapRejected.failure");
    }

    @Override
    public String toString() {
        return "FreshExecutionBootstrapRejected[failure=<provided>]";
    }
}
