package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;

import java.time.Instant;
import java.util.Optional;

public record ActiveStepCompletionEventDraft(
        EventId id,
        Instant occurredAt,
        EventType type,
        Optional<EventId> causationId,
        String correlationId,
        EventPayload payload) {

    public ActiveStepCompletionEventDraft {
        ActiveStepCompletionMaterializationValues.required(
                id, "completionEventDraft.id");
        ActiveStepCompletionMaterializationValues.required(
                occurredAt, "completionEventDraft.occurredAt");
        ActiveStepCompletionMaterializationValues.required(
                type, "completionEventDraft.type");
        causationId = ActiveStepCompletionMaterializationValues.required(
                causationId, "completionEventDraft.causationId");
        correlationId = ActiveStepCompletionMaterializationValues.text(
                correlationId, "completionEventDraft.correlationId");
        ActiveStepCompletionMaterializationValues.required(
                payload, "completionEventDraft.payload");
        if (causationId.filter(id::equals).isPresent()) {
            throw ActiveStepCompletionMaterializationValues.validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .EVENT_SELF_CAUSATION,
                    ActiveStepCompletionMaterializationStage.EVENT,
                    "completionEventDraft.causationId");
        }
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionEventDraft[id=<redacted>, "
                + "occurredAt=<provided>, type=<provided>, "
                + "causationId=<redacted>, correlationId=<redacted>, "
                + "payload=<redacted>]";
    }
}
