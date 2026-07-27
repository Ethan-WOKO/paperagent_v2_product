package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ProductStepActivationTestFixtures {
    static final Instant NOW = ProductExecutionStartTestFixtures.NOW;

    private ProductStepActivationTestFixtures() {
    }

    static StepActivationRequest request(
            PersistedPlanBootstrap bootstrap, String token, long fence,
            String eventId) {
        ExecutionStartRequest start = ProductExecutionStartTestFixtures.request(
                bootstrap, token, fence, "start-" + bootstrap.plan().id().value());
        Checkpoint h0 = start.startedCheckpoint();
        PlanStepId step = new PlanStepId("step-a");
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(h0.stepStates());
        states.put(step, StepExecutionState.ACTIVE);
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId), bootstrap.taskFrame().id(),
                bootstrap.plan().id(), 2, NOW.plusSeconds(1),
                new EventType("STEP_ACTIVATED"),
                Optional.of(start.startEvent().id()),
                "activation-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint activated = new Checkpoint(
                h0.taskFrameId(), h0.planId(), h0.revisionId(),
                h0.revisionNumber(), 2, PlanExecutionState.ACTIVE,
                states, List.of(), h0.createdAt().plusSeconds(1));
        return new StepActivationRequest(
                bootstrap.plan().id(), token, fence,
                bootstrap.plan().latestRevision().id(),
                bootstrap.plan().latestRevision().number(),
                2, 1, step, event, activated);
    }

    static void seedH0(
            PersistedPlanBootstrap bootstrap,
            String owner, String token, long fence,
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductLeaseJpaRepository leases,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec) {
        ProductPlanExecutionContextTestFixtures.seedStarted(
                bootstrap, owner, token, fence, bootstraps, bootstrapCodec,
                leases, starts, startCodec);
    }

    static void seedConfirmedContext(
            PersistedPlanBootstrap bootstrap, String owner, String token,
            long fence,
            ProductPlanExecutionContextJpaRepository contexts,
            ProductPlanExecutionContextCodec codec) {
        var spec = ProductPlanExecutionContextTestFixtures.spec(
                bootstrap.plan().id().value());
        var reservation =
                ProductPlanExecutionContextTestFixtures.reservation(
                        bootstrap, token, fence, spec);
        var reserved = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                bootstrap.plan().id(), spec, owner, fence);
        var confirmation =
                ProductPlanExecutionContextTestFixtures.confirmation(
                        bootstrap, token, fence, spec);
        var confirmed = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed(
                reserved, owner, fence,
                ProductPlanExecutionContextTestFixtures.FINGERPRINT);
        ProductPlanExecutionContextEntity row =
                new ProductPlanExecutionContextEntity(
                        bootstrap.plan().id().value(),
                        spec.workspaceId().value(), owner, fence,
                        codec.encodeReservationRequest(reservation),
                        codec.encodeReservationResult(reserved));
        row.confirm(owner, fence,
                codec.encodeConfirmationRequest(confirmation),
                codec.encodeConfirmationResult(confirmed),
                ProductPlanExecutionContextTestFixtures.FINGERPRINT.value());
        contexts.saveAndFlush(row);
    }
}
