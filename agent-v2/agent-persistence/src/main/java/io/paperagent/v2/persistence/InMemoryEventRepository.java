package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

final class InMemoryEventRepository implements EventRepository {
    private final InMemoryState state;

    InMemoryEventRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<EventEnvelope> append(EventEnvelope event) {
        if (PersistenceChecks.missing(event)) {
            return PersistenceChecks.invalid("event");
        }
        synchronized (state.monitor) {
            EventEnvelope existing = state.eventsById.get(event.id());
            if (existing != null) {
                return existing.equals(event)
                        ? PersistenceResult.replayed(existing)
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY, "event.id");
            }
            if (state.executionStarts.containsKey(event.planId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                        "event.planId");
            }
            Plan plan = state.plans.get(event.planId());
            if (plan == null) {
                return PersistenceChecks.notFound("event.planId");
            }
            if (!plan.taskFrameId().equals(event.taskFrameId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.TASK_FRAME_MISMATCH, "event.taskFrameId");
            }
            NavigableMap<Long, EventEnvelope> stream =
                    state.eventStreams.get(event.planId());
            if (stream != null && !stream.isEmpty()) {
                EventEnvelope previous = stream.lastEntry().getValue();
                if (!EventValidators.validateNext(previous, event).isEmpty()) {
                    return PersistenceResult.rejected(
                            PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC, "event.sequence");
                }
            }
            if (stream == null) {
                stream = new TreeMap<>();
                state.eventStreams.put(event.planId(), stream);
            }
            stream.put(event.sequence(), event);
            state.eventsById.put(event.id(), event);
            return PersistenceResult.applied(event);
        }
    }

    @Override
    public PersistenceResult<EventEnvelope> find(EventId eventId) {
        if (PersistenceChecks.missing(eventId)) {
            return PersistenceChecks.invalid("eventId");
        }
        synchronized (state.monitor) {
            EventEnvelope event = state.eventsById.get(eventId);
            return event == null
                    ? PersistenceChecks.notFound("eventId")
                    : PersistenceResult.found(event);
        }
    }

    @Override
    public PersistenceResult<List<EventEnvelope>> readAfter(
            PlanId planId,
            long exclusiveSequence) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (exclusiveSequence < 0) {
            return PersistenceChecks.invalid("exclusiveSequence");
        }
        synchronized (state.monitor) {
            if (!state.plans.containsKey(planId)) {
                return PersistenceChecks.notFound("planId");
            }
            NavigableMap<Long, EventEnvelope> stream =
                    state.eventStreams.get(planId);
            List<EventEnvelope> snapshot = stream == null
                    ? List.of()
                    : List.copyOf(
                            stream.tailMap(exclusiveSequence, false).values());
            return PersistenceResult.found(snapshot);
        }
    }
}
