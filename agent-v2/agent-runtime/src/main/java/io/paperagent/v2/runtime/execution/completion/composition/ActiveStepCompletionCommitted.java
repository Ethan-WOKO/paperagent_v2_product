package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistenceOutcome;

public record ActiveStepCompletionCommitted(
        PersistenceOutcome persistenceOutcome,
        PersistedStepCompletion persistedCompletion,
        ActiveStepCompletionLeaseDisposition leaseDisposition)
        implements ActiveStepCompletionCompositionOutcome {

    public ActiveStepCompletionCommitted {
        ActiveStepCompletionCompositionValues.requireCommitted(
                persistenceOutcome, persistedCompletion, leaseDisposition);
    }

    @Override
    public PlanId planId() {
        return persistedCompletion.planId();
    }

    @Override
    public PlanStepId stepId() {
        return persistedCompletion.stepId();
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionCommitted[persistenceOutcome="
                + persistenceOutcome + ", persistedCompletion=<provided>, "
                + "leaseDisposition=" + leaseDisposition + "]";
    }
}
