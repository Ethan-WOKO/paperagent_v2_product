package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.StepCompletionRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ProductStepCompletionTestFixtures {
    static final Instant NOW = ProductStepActivationTestFixtures.NOW;

    private ProductStepCompletionTestFixtures() {
    }

    static StepCompletionRequest request(
            ProductEffectIntentTestFixtures.Scenario scenario,
            String token, long fence, String eventId,
            List<ReceiptId> receipts) {
        var bootstrap = scenario.bootstrap();
        var activation = scenario.persistedActivation();
        PlanRevision source = bootstrap.plan().latestRevision();
        CompletionFact fact = new CompletionFact(
                activation.stepId(), "outcome-hash",
                NOW.plusSeconds(2), receipts);
        PlanRevision completed = new PlanRevision(
                new PlanRevisionId("revision-completed-" + eventId),
                source.taskFrameId(), source.number() + 1,
                Optional.of(source.id()), "step completed",
                NOW.plusSeconds(2), source.steps(),
                Map.of(activation.stepId(), fact));
        Checkpoint active = activation.activatedCheckpoint().checkpoint();
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(active.stepStates());
        states.put(activation.stepId(), StepExecutionState.SUCCEEDED);
        boolean allSucceeded = states.values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId), bootstrap.taskFrame().id(),
                bootstrap.plan().id(), 3, NOW.plusSeconds(2),
                new EventType("STEP_COMPLETED"),
                Optional.of(activation.activationEvent().id()),
                "completion-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                active.taskFrameId(), active.planId(), completed.id(),
                completed.number(), 3, allSucceeded
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.ACTIVE,
                states, receipts, NOW.plusSeconds(2));
        return new StepCompletionRequest(
                bootstrap.plan().id(), token, fence, source.id(),
                source.number(), 3, 2, activation.stepId(), fact,
                event, completed, checkpoint);
    }
}
