package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.Optional;

/**
 * One immutable event in a Plan-global sequence.
 *
 * <p>{@code sequence} is strictly increasing within one {@link PlanId};
 * {@code correlationId} is tracing metadata and does not partition storage or
 * recovery.
 */
public record EventEnvelope(
        EventId id,
        TaskFrameId taskFrameId,
        PlanId planId,
        long sequence,
        Instant occurredAt,
        EventType type,
        Optional<EventId> causationId,
        String correlationId,
        EventPayload payload) {

    public EventEnvelope {
        Contracts.required(id, "event.id");
        Contracts.required(taskFrameId, "event.taskFrameId");
        Contracts.required(planId, "event.planId");
        if (sequence < 1) {
            Contracts.fail(ViolationCode.EVENT_SEQUENCE_REGRESSION, "event.sequence",
                    "event sequence must be positive");
        }
        Contracts.required(occurredAt, "event.occurredAt");
        Contracts.required(type, "event.type");
        causationId = Contracts.required(causationId, "event.causationId");
        correlationId = Contracts.id(correlationId, "event.correlationId");
        Contracts.required(payload, "event.payload");
        if (causationId.filter(id::equals).isPresent()) {
            Contracts.fail(ViolationCode.INCONSISTENT_REFERENCE, "event.causationId",
                    "an event cannot cause itself");
        }
    }
}
