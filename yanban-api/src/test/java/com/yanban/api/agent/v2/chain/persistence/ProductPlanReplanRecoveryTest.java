package com.yanban.api.agent.v2.persistence;

import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedPlanReplan;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PlanReplanRequest;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPlanReplanRecoveryTest {
    private static final Instant NOW =
            Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void foldsAnOrdinaryReplanBeforeTheNextActivation() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "plan-recovery", "frame-recovery");
        ExecutionStartRequest startRequest =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "lease-token", 3, "start-recovery");
        PersistedExecutionStart startResult = new PersistedExecutionStart(
                bootstrap.plan().id(), "owner-1", 3,
                startRequest.startEvent(), new VersionedCheckpoint(
                2, startRequest.startedCheckpoint()));

        ProductPlanBootstrapJpaRepository bootstraps =
                mock(ProductPlanBootstrapJpaRepository.class);
        ProductPlanBootstrapCodec bootstrapCodec =
                mock(ProductPlanBootstrapCodec.class);
        ProductExecutionStartJpaRepository starts =
                mock(ProductExecutionStartJpaRepository.class);
        ProductExecutionStartCodec startCodec =
                mock(ProductExecutionStartCodec.class);
        ProductPlanExecutionContextJpaRepository contexts =
                mock(ProductPlanExecutionContextJpaRepository.class);
        ProductPlanExecutionContextCodec contextCodec =
                mock(ProductPlanExecutionContextCodec.class);
        ProductStepActivationJpaRepository activations =
                mock(ProductStepActivationJpaRepository.class);
        ProductStepActivationCodec activationCodec =
                mock(ProductStepActivationCodec.class);
        ProductStepInterruptionJpaRepository interruptions =
                mock(ProductStepInterruptionJpaRepository.class);
        ProductStepInterruptionMarkerReader interruptionMarkers =
                mock(ProductStepInterruptionMarkerReader.class);
        ProductStepCompletionJpaRepository completions =
                mock(ProductStepCompletionJpaRepository.class);
        ProductStepCompletionMarkerReader completionMarkers =
                mock(ProductStepCompletionMarkerReader.class);
        ProductActiveStepReplanJpaRepository activeReplans =
                mock(ProductActiveStepReplanJpaRepository.class);
        ProductActiveStepReplanMarkerReader activeReplanMarkers =
                mock(ProductActiveStepReplanMarkerReader.class);
        ProductPlanReplanMarkerReader ordinaryReplans =
                mock(ProductPlanReplanMarkerReader.class);

        ProductPlanBootstrapEntity bootstrapRow =
                new ProductPlanBootstrapEntity(
                        bootstrap.plan().id().value(),
                        bootstrap.taskFrame().id().value(), 1,
                        "0".repeat(64), "{}", NOW);
        var encoded = new ProductExecutionStartCodec.EncodedPayload(
                1, "0".repeat(64), "{}");
        ProductExecutionStartEntity startRow =
                new ProductExecutionStartEntity(
                        bootstrap.plan().id().value(),
                        startRequest.startEvent().id().value(), "owner-1", 3,
                        encoded, encoded, NOW);
        when(bootstraps.lockByPlanIdForInspection(
                bootstrap.plan().id().value()))
                .thenReturn(Optional.of(bootstrapRow));
        when(bootstrapCodec.decode(anyInt(), anyString(), anyString()))
                .thenReturn(bootstrap);
        when(starts.findById(bootstrap.plan().id().value()))
                .thenReturn(Optional.of(startRow));
        when(startCodec.decodeRequest(anyInt(), anyString(), anyString()))
                .thenReturn(startRequest);
        when(startCodec.decodeResult(anyInt(), anyString(), anyString()))
                .thenReturn(startResult);
        when(contexts.findById(bootstrap.plan().id().value()))
                .thenReturn(Optional.empty());
        when(activations.findAllByPlanIdOrderBySourceEventSequenceAsc(
                bootstrap.plan().id().value())).thenReturn(List.of());
        when(interruptions.findAllByPlanId(
                bootstrap.plan().id().value())).thenReturn(List.of());
        when(completions.findAllByPlanIdOrderBySourceEventSequenceAsc(
                bootstrap.plan().id().value())).thenReturn(List.of());
        when(activeReplans.findAllByPlanIdOrderBySourceEventSequenceAsc(
                bootstrap.plan().id().value())).thenReturn(List.of());

        ProductPlanReplanMarkerReader.Marker marker = marker(
                bootstrap, startResult.startedCheckpoint());
        when(ordinaryReplans.findAllByPlanId(
                bootstrap.plan().id().value()))
                .thenReturn(List.of(marker));
        ProductStepRecoveryTransactions recovery =
                new ProductStepRecoveryTransactions(
                        bootstraps, bootstrapCodec, starts, startCodec,
                        contexts, contextCodec, activations, activationCodec,
                        interruptions, interruptionMarkers, completions,
                        completionMarkers, activeReplans,
                        activeReplanMarkers, ordinaryReplans);

        var inspected = recovery.inspectLocked(bootstrap.plan().id());

        assertEquals(PersistenceOutcome.FOUND, inspected.outcome());
        PersistedStepRecoveryReady ready = assertInstanceOf(
                PersistedStepRecoveryReady.class,
                inspected.value().orElseThrow());
        assertEquals(2, ready.plan().latestRevision().number());
        assertEquals(3, ready.checkpoint().version());
        assertEquals(2,
                ready.checkpoint().checkpoint().lastEventSequence());
        assertEquals(new PlanStepId("step-a"), ready.readyStepId());
    }

    @Test
    void returnsPartialForAnOrdinaryReplanDuringAnActiveStep() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "plan-active", "frame-active");
        ExecutionStartRequest startRequest =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "lease-token", 3, "start-active");
        PersistedExecutionStart startResult = new PersistedExecutionStart(
                bootstrap.plan().id(), "owner-1", 3,
                startRequest.startEvent(), new VersionedCheckpoint(
                2, startRequest.startedCheckpoint()));

        ProductPlanBootstrapJpaRepository bootstraps =
                mock(ProductPlanBootstrapJpaRepository.class);
        ProductPlanBootstrapCodec bootstrapCodec =
                mock(ProductPlanBootstrapCodec.class);
        ProductExecutionStartJpaRepository starts =
                mock(ProductExecutionStartJpaRepository.class);
        ProductExecutionStartCodec startCodec =
                mock(ProductExecutionStartCodec.class);
        ProductPlanExecutionContextJpaRepository contexts =
                mock(ProductPlanExecutionContextJpaRepository.class);
        ProductPlanExecutionContextCodec contextCodec =
                mock(ProductPlanExecutionContextCodec.class);
        ProductStepActivationJpaRepository activations =
                mock(ProductStepActivationJpaRepository.class);
        ProductStepActivationCodec activationCodec =
                mock(ProductStepActivationCodec.class);
        ProductStepInterruptionJpaRepository interruptions =
                mock(ProductStepInterruptionJpaRepository.class);
        ProductStepInterruptionMarkerReader interruptionMarkers =
                mock(ProductStepInterruptionMarkerReader.class);
        ProductStepCompletionJpaRepository completions =
                mock(ProductStepCompletionJpaRepository.class);
        ProductStepCompletionMarkerReader completionMarkers =
                mock(ProductStepCompletionMarkerReader.class);
        ProductActiveStepReplanJpaRepository activeReplans =
                mock(ProductActiveStepReplanJpaRepository.class);
        ProductActiveStepReplanMarkerReader activeReplanMarkers =
                mock(ProductActiveStepReplanMarkerReader.class);
        ProductPlanReplanMarkerReader ordinaryReplans =
                mock(ProductPlanReplanMarkerReader.class);

        ProductPlanBootstrapEntity bootstrapRow =
                new ProductPlanBootstrapEntity(
                        bootstrap.plan().id().value(),
                        bootstrap.taskFrame().id().value(), 1,
                        "0".repeat(64), "{}", NOW);
        var encoded = new ProductExecutionStartCodec.EncodedPayload(
                1, "0".repeat(64), "{}");
        ProductExecutionStartEntity startRow =
                new ProductExecutionStartEntity(
                        bootstrap.plan().id().value(),
                        startRequest.startEvent().id().value(), "owner-1", 3,
                        encoded, encoded, NOW);
        when(bootstraps.lockByPlanIdForInspection(
                bootstrap.plan().id().value()))
                .thenReturn(Optional.of(bootstrapRow));
        when(bootstrapCodec.decode(anyInt(), anyString(), anyString()))
                .thenReturn(bootstrap);
        when(starts.findById(bootstrap.plan().id().value()))
                .thenReturn(Optional.of(startRow));
        when(startCodec.decodeRequest(anyInt(), anyString(), anyString()))
                .thenReturn(startRequest);
        when(startCodec.decodeResult(anyInt(), anyString(), anyString()))
                .thenReturn(startResult);
        when(contexts.findById(bootstrap.plan().id().value()))
                .thenReturn(Optional.empty());

        PlanStepId activeStep = new PlanStepId("step-a");
        Checkpoint source = startResult.startedCheckpoint().checkpoint();
        Map<PlanStepId, StepExecutionState> activeStates =
                new LinkedHashMap<>(source.stepStates());
        activeStates.put(activeStep, StepExecutionState.ACTIVE);
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-active"),
                bootstrap.taskFrame().id(), bootstrap.plan().id(), 2,
                NOW.minusSeconds(2), new EventType("STEP_ACTIVATED"),
                Optional.of(startRequest.startEvent().id()),
                "activation-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint activatedCheckpoint = new Checkpoint(
                bootstrap.taskFrame().id(), bootstrap.plan().id(),
                bootstrap.plan().latestRevision().id(), 1, 2,
                PlanExecutionState.ACTIVE, activeStates,
                source.receiptReferences(), NOW.minusSeconds(2));
        StepActivationRequest activationRequest = new StepActivationRequest(
                bootstrap.plan().id(), "lease-token", 3,
                bootstrap.plan().latestRevision().id(), 1, 2, 1,
                activeStep, activationEvent, activatedCheckpoint);
        PersistedStepActivation activationResult =
                new PersistedStepActivation(
                        bootstrap.plan().id(), activeStep, "owner-1", 3,
                        activationEvent,
                        new VersionedCheckpoint(3, activatedCheckpoint));
        var activationPayload =
                new ProductStepActivationCodec.EncodedPayload(
                        1, "0".repeat(64), "{}");
        ProductStepActivationEntity activationRow =
                new ProductStepActivationEntity(
                        bootstrap.plan().id().value(), activeStep.value(),
                        activationEvent.id().value(),
                        bootstrap.plan().latestRevision().id().value(), 1,
                        bootstrap.plan().latestRevision().id().value(), 1,
                        2, 3, 1, 2, "owner-1", 3,
                        activationPayload, activationPayload, NOW);
        when(activationCodec.decodeRequest(
                anyInt(), anyString(), anyString()))
                .thenReturn(activationRequest);
        when(activationCodec.decodeResult(
                anyInt(), anyString(), anyString()))
                .thenReturn(activationResult);
        when(activations.findAllByPlanIdOrderBySourceEventSequenceAsc(
                bootstrap.plan().id().value()))
                .thenReturn(List.of(activationRow));
        when(interruptions.findAllByPlanId(
                bootstrap.plan().id().value())).thenReturn(List.of());
        when(completions.findAllByPlanIdOrderBySourceEventSequenceAsc(
                bootstrap.plan().id().value())).thenReturn(List.of());
        when(activeReplans.findAllByPlanIdOrderBySourceEventSequenceAsc(
                bootstrap.plan().id().value())).thenReturn(List.of());
        when(ordinaryReplans.findAllByPlanId(
                bootstrap.plan().id().value())).thenReturn(
                List.of(), List.of(marker(
                        bootstrap, new VersionedCheckpoint(
                                3, activatedCheckpoint))));

        ProductStepRecoveryTransactions recovery =
                new ProductStepRecoveryTransactions(
                        bootstraps, bootstrapCodec, starts, startCodec,
                        contexts, contextCodec, activations, activationCodec,
                        interruptions, interruptionMarkers, completions,
                        completionMarkers, activeReplans,
                        activeReplanMarkers, ordinaryReplans);

        var active = recovery.inspectLocked(bootstrap.plan().id());
        var inspected = recovery.inspectLocked(bootstrap.plan().id());

        assertEquals(PersistenceOutcome.FOUND, active.outcome());
        assertInstanceOf(PersistedStepRecoveryActive.class,
                active.value().orElseThrow());
        assertEquals(PersistenceOutcome.REJECTED, inspected.outcome());
        assertEquals(PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                inspected.failure().orElseThrow().code());
    }

    private static ProductPlanReplanMarkerReader.Marker marker(
            PersistedPlanBootstrap bootstrap,
            VersionedCheckpoint source) {
        PlanRevision previous = bootstrap.plan().latestRevision();
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-2"),
                bootstrap.taskFrame().id(), 2,
                Optional.of(previous.id()), "ordinary replan", NOW,
                previous.steps(), previous.completedFacts());
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        revision.steps().forEach(step -> states.put(
                step.id(), StepExecutionState.NOT_STARTED));
        long eventSequence =
                source.checkpoint().lastEventSequence() + 1;
        EventEnvelope event = new EventEnvelope(
                new EventId("ordinary-replan-event"),
                bootstrap.taskFrame().id(), bootstrap.plan().id(),
                eventSequence, NOW,
                new EventType("PLAN_REPLANNED"),
                Optional.of(source.checkpoint().revisionId()
                        .equals(previous.id())
                        ? new EventId("start-recovery")
                        : new EventId("unreachable")),
                "ordinary-replan-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                bootstrap.taskFrame().id(), bootstrap.plan().id(),
                revision.id(), revision.number(), eventSequence,
                PlanExecutionState.ACTIVE, states,
                source.checkpoint().receiptReferences(), NOW);
        PlanReplanRequest request = new PlanReplanRequest(
                bootstrap.plan().id(), "lease-token", 3,
                previous.id(), previous.number(), source.version(),
                source.checkpoint().lastEventSequence(), event,
                revision, checkpoint);
        PersistedPlanReplan result = new PersistedPlanReplan(
                bootstrap.plan().id(), "owner-1", 3, event, revision,
                new VersionedCheckpoint(source.version() + 1, checkpoint));
        return new ProductPlanReplanMarkerReader.Marker(
                "task-1", source.checkpoint().lastEventSequence(),
                event.sequence(), request, result, NOW);
    }
}
