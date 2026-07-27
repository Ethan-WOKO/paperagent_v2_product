package io.paperagent.v2.runtime.execution.recovery.materialization;

import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;

import java.time.Instant;

public record RecoveryReadyExecutionStartMaterializationRequest(
        PersistedExecutionStartReady ready,
        ExecutionStartEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public RecoveryReadyExecutionStartMaterializationRequest {
        RecoveryReadyExecutionStartMaterializationValues.required(
                ready,
                "recoveryReadyExecutionStartMaterializationRequest.ready");
        RecoveryReadyExecutionStartMaterializationValues.required(
                eventDraft,
                "recoveryReadyExecutionStartMaterializationRequest.eventDraft");
        RecoveryReadyExecutionStartMaterializationValues.required(
                checkpointCreatedAt,
                "recoveryReadyExecutionStartMaterializationRequest"
                        + ".checkpointCreatedAt");
    }

    @Override
    public String toString() {
        return "RecoveryReadyExecutionStartMaterializationRequest"
                + "[ready=<provided>, eventDraft=<provided>, "
                + "checkpointCreatedAt=<provided>]";
    }
}
