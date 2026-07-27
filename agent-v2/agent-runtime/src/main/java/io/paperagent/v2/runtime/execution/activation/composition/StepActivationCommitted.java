package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistenceOutcome;

public record StepActivationCommitted(
        PersistenceOutcome activationOutcome,
        PersistedStepActivation persistedActivation,
        StepActivationLeaseDisposition leaseDisposition)
        implements StepActivationCompositionOutcome {

    public StepActivationCommitted {
        StepActivationCompositionValues.requireCommitted(
                activationOutcome, persistedActivation, leaseDisposition);
    }

    @Override
    public PlanId planId() {
        return persistedActivation.planId();
    }

    @Override
    public String toString() {
        return "StepActivationCommitted[activationOutcome=" + activationOutcome
                + ", persistedActivation=<provided>, leaseDisposition="
                + leaseDisposition + "]";
    }
}
