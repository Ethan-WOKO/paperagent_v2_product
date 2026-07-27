package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;

import java.time.Instant;
import java.util.Optional;

public record StepActivationEventDraft(
        EventId id,
        Instant occurredAt,
        EventType type,
        Optional<EventId> causationId,
        String correlationId,
        EventPayload payload) {

    public StepActivationEventDraft {
        CommittedStepActivationMaterializationValues.required(
                id,
                "stepActivationEventDraft.id");
        CommittedStepActivationMaterializationValues.required(
                occurredAt,
                "stepActivationEventDraft.occurredAt");
        CommittedStepActivationMaterializationValues.required(
                type,
                "stepActivationEventDraft.type");
        causationId =
                CommittedStepActivationMaterializationValues.required(
                        causationId,
                        "stepActivationEventDraft.causationId");
        correlationId =
                CommittedStepActivationMaterializationValues.identifier(
                        correlationId,
                        "stepActivationEventDraft.correlationId");
        CommittedStepActivationMaterializationValues.required(
                payload,
                "stepActivationEventDraft.payload");
    }

    @Override
    public String toString() {
        return "StepActivationEventDraft[id=<provided>, "
                + "occurredAt=<provided>, type=<provided>, "
                + "causationId=<provided>, correlationId=<provided>, "
                + "payload=<provided>]";
    }
}
