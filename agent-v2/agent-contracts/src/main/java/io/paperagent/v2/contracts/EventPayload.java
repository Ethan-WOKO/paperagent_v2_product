package io.paperagent.v2.contracts;

public sealed interface EventPayload permits InlineEventPayload, EventPayloadRef {
}
