package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;

/**
 * Snapshot-derived event and checkpoint proposals for one Step activation.
 *
 * <p>This value does not establish authority, persistence, or successful Step
 * activation.
 */
public record MaterializedStepActivation(
        EventEnvelope activationEvent,
        Checkpoint activatedCheckpoint) {

    public MaterializedStepActivation {
        CommittedStepActivationMaterializationValues.required(
                activationEvent,
                "materializedStepActivation.activationEvent");
        CommittedStepActivationMaterializationValues.required(
                activatedCheckpoint,
                "materializedStepActivation.activatedCheckpoint");
    }

    @Override
    public String toString() {
        return "MaterializedStepActivation[activationEvent=<provided>, "
                + "activatedCheckpoint=<provided>]";
    }
}
