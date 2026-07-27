package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;

import java.util.List;

public interface EventRepository {
    PersistenceResult<EventEnvelope> append(EventEnvelope event);

    PersistenceResult<EventEnvelope> find(EventId eventId);

    PersistenceResult<List<EventEnvelope>> readAfter(
            PlanId planId,
            long exclusiveSequence);
}
