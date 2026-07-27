package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;

/**
 * Snapshot-derived event and checkpoint proposals for atomic start.
 *
 * <p>These values do not claim current-state eligibility or persistence.
 */
public record MaterializedExecutionStart(
        EventEnvelope startEvent,
        Checkpoint startedCheckpoint) {

    public MaterializedExecutionStart {
        ExecutionStartMaterializationValues.required(
                startEvent,
                "materializedExecutionStart.startEvent");
        ExecutionStartMaterializationValues.required(
                startedCheckpoint,
                "materializedExecutionStart.startedCheckpoint");
    }
}
