package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceOutcome;

public record ActiveStepInterruptionCommitted(
        PersistenceOutcome persistenceOutcome,
        PersistedStepInterruption persistedInterruption,
        ActiveStepInterruptionLeaseDisposition leaseDisposition)
        implements ActiveStepInterruptionCompositionOutcome {

    public ActiveStepInterruptionCommitted {
        ActiveStepInterruptionCompositionValues.requireCommitted(
                persistenceOutcome, persistedInterruption, leaseDisposition);
    }

    @Override
    public PlanId planId() {
        return persistedInterruption.planId();
    }

    @Override
    public String toString() {
        return "ActiveStepInterruptionCommitted[persistenceOutcome="
                + persistenceOutcome + ", persistedInterruption=<provided>, "
                + "leaseDisposition=" + leaseDisposition + "]";
    }
}
