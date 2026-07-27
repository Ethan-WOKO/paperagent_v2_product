package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventPayloadRef;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ProductExecutionStartTestFixtures {
    static final Instant NOW = Instant.parse("2026-07-27T10:00:00.123456Z");

    private ProductExecutionStartTestFixtures() {
    }

    static PersistedPlanBootstrap bootstrap(String planId, String taskId) {
        return ProductPlanBootstrapTestFixtures.workspace(planId, taskId);
    }

    static ExecutionStartRequest request(
            PersistedPlanBootstrap bootstrap, String leaseToken, long fence,
            String eventId) {
        return request(bootstrap, leaseToken, fence, eventId, inlinePayload());
    }

    static ExecutionStartRequest request(
            PersistedPlanBootstrap bootstrap, String leaseToken, long fence,
            String eventId, EventPayload payload) {
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId),
                bootstrap.taskFrame().id(),
                bootstrap.plan().id(),
                1,
                NOW.plusNanos(789),
                new EventType("PLAN_STARTED"),
                Optional.of(new EventId("synthetic-cause")),
                "synthetic-correlation",
                payload);
        Checkpoint source = bootstrap.initialCheckpoint().checkpoint();
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        source.stepStates().keySet().stream()
                .sorted((left, right) ->
                        right.value().compareTo(left.value()))
                .forEach(id -> states.put(id, StepExecutionState.NOT_STARTED));
        Checkpoint started = new Checkpoint(
                source.taskFrameId(),
                source.planId(),
                source.revisionId(),
                source.revisionNumber(),
                1,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                source.createdAt().plusSeconds(1).plusNanos(321));
        return new ExecutionStartRequest(
                bootstrap.plan().id(), leaseToken, fence, event, started);
    }

    static InlineEventPayload inlinePayload() {
        Map<String, io.paperagent.v2.contracts.ContractValue> nested =
                new LinkedHashMap<>();
        nested.put("z-null", NullValue.INSTANCE);
        nested.put("a-number", new NumberValue(new BigDecimal("1.2300")));
        nested.put("m-list", new ListValue(List.of(
                new TextValue("synthetic"),
                new BooleanValue(true),
                new ObjectValue(Map.of("inner", new TextValue("value"))))));
        return new InlineEventPayload(new ObjectValue(nested));
    }

    static EventPayloadRef referencePayload() {
        return new EventPayloadRef("synthetic-event-payload");
    }
}
