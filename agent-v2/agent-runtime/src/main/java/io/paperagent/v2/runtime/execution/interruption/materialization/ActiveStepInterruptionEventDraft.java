package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;

import java.time.Instant;
import java.util.Optional;

public record ActiveStepInterruptionEventDraft(
        EventId id,
        Instant occurredAt,
        EventType type,
        Optional<EventId> causationId,
        String correlationId,
        EventPayload payload) {

    public ActiveStepInterruptionEventDraft {
        ActiveStepInterruptionMaterializationValues.required(
                id, "interruptionEventDraft.id");
        ActiveStepInterruptionMaterializationValues.required(
                occurredAt, "interruptionEventDraft.occurredAt");
        ActiveStepInterruptionMaterializationValues.required(
                type, "interruptionEventDraft.type");
        causationId = ActiveStepInterruptionMaterializationValues.required(
                causationId, "interruptionEventDraft.causationId");
        correlationId = ActiveStepInterruptionMaterializationValues.identifier(
                correlationId, "interruptionEventDraft.correlationId");
        ActiveStepInterruptionMaterializationValues.required(
                payload, "interruptionEventDraft.payload");
        if (causationId.filter(id::equals).isPresent()) {
            throw ActiveStepInterruptionMaterializationValues.validation(
                    ActiveStepInterruptionMaterializationValidationCode
                            .EVENT_SELF_CAUSATION,
                    ActiveStepInterruptionMaterializationStage.EVENT,
                    "interruptionEventDraft.causationId");
        }
    }

    @Override
    public String toString() {
        return "ActiveStepInterruptionEventDraft[id=<provided>, "
                + "occurredAt=<provided>, type=<provided>, "
                + "causationId=<provided>, correlationId=<provided>, "
                + "payload=<provided>]";
    }
}
