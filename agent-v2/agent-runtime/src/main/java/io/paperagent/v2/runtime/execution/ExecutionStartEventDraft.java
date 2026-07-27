package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;

import java.time.Instant;
import java.util.Optional;

public record ExecutionStartEventDraft(
        EventId id,
        Instant occurredAt,
        EventType type,
        Optional<EventId> causationId,
        String correlationId,
        EventPayload payload) {

    public ExecutionStartEventDraft {
        ExecutionStartMaterializationValues.required(
                id,
                "executionStartEventDraft.id");
        ExecutionStartMaterializationValues.required(
                occurredAt,
                "executionStartEventDraft.occurredAt");
        ExecutionStartMaterializationValues.required(
                type,
                "executionStartEventDraft.type");
        causationId = ExecutionStartMaterializationValues.required(
                causationId,
                "executionStartEventDraft.causationId");
        correlationId = ExecutionStartMaterializationValues.identifier(
                correlationId,
                "executionStartEventDraft.correlationId");
        ExecutionStartMaterializationValues.required(
                payload,
                "executionStartEventDraft.payload");
    }
}
