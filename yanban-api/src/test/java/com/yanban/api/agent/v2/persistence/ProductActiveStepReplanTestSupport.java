package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ProductActiveStepReplanTestSupport {
    private ProductActiveStepReplanTestSupport() {
    }

    static ActiveStepReplanRequest request(String suffix) {
        var bootstrap = ProductPlanBootstrapTestFixtures.workspace(
                "replan-" + suffix, "task-" + suffix);
        var activation = ProductStepActivationTestFixtures.request(
                bootstrap, "lease-token", 3,
                "activation-" + suffix);
        Checkpoint active = activation.activatedCheckpoint();
        Map<PlanStepId, StepExecutionState> supersededStates =
                new LinkedHashMap<>(active.stepStates());
        supersededStates.put(
                activation.stepId(),
                StepExecutionState.SUPERSEDED_BY_REPLAN);
        EventEnvelope supersession = new EventEnvelope(
                new EventId("supersession-" + suffix),
                bootstrap.taskFrame().id(), bootstrap.plan().id(),
                3, ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                new EventType("STEP_SUPERSEDED_BY_REPLAN"),
                Optional.of(activation.activationEvent().id()),
                "replan-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint supersededCheckpoint = new Checkpoint(
                active.taskFrameId(), active.planId(),
                active.revisionId(), active.revisionNumber(),
                3, PlanExecutionState.ACTIVE,
                supersededStates, active.receiptReferences(),
                active.createdAt().plusSeconds(1));
        PlanStep replacement = new PlanStep(
                new PlanStepId("replacement-" + suffix),
                "Recover with a replacement",
                "Replacement completes",
                Set.of(), List.of("replacement complete"),
                new BoundedExecutionHints(
                        2, Duration.ofMinutes(1)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-2-" + suffix),
                bootstrap.taskFrame().id(), 2,
                Optional.of(
                        bootstrap.plan().latestRevision().id()),
                "bounded replan",
                ProductStepActivationTestFixtures.NOW.plusSeconds(3),
                List.of(replacement), Map.of());
        EventEnvelope replan = new EventEnvelope(
                new EventId("replan-" + suffix),
                bootstrap.taskFrame().id(), bootstrap.plan().id(),
                4, ProductStepActivationTestFixtures.NOW.plusSeconds(3),
                new EventType("PLAN_REPLANNED"),
                Optional.of(supersession.id()),
                "replan-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint replacementCheckpoint = new Checkpoint(
                active.taskFrameId(), active.planId(),
                revision.id(), revision.number(),
                4, PlanExecutionState.ACTIVE,
                Map.of(replacement.id(),
                        StepExecutionState.NOT_STARTED),
                active.receiptReferences(),
                active.createdAt().plusSeconds(2));
        return new ActiveStepReplanRequest(
                bootstrap.plan().id(), "lease-token", 3,
                active.revisionId(), active.revisionNumber(),
                3, 2, activation.stepId(),
                supersession, supersededCheckpoint,
                replan, revision, replacementCheckpoint);
    }

    static PersistedActiveStepReplan result(
            ActiveStepReplanRequest request) {
        return new PersistedActiveStepReplan(
                request.planId(), request.activeStepId(),
                "owner", request.fencingToken(),
                request.supersessionEvent(),
                new VersionedCheckpoint(
                        request.expectedCheckpointVersion() + 1,
                        request.supersededCheckpoint()),
                request.replanEvent(),
                request.replannedRevision(),
                new VersionedCheckpoint(
                        request.expectedCheckpointVersion() + 2,
                        request.replannedCheckpoint()));
    }

    static StepActivationRequest activationAfter(
            PersistedActiveStepReplan replan, String leaseToken,
            String suffix) {
        Checkpoint ready = replan.replannedCheckpoint().checkpoint();
        PlanStepId stepId = replan.replannedRevision().steps().get(0).id();
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(ready.stepStates());
        states.put(stepId, StepExecutionState.ACTIVE);
        EventEnvelope event = new EventEnvelope(
                new EventId("activation-" + suffix),
                ready.taskFrameId(), ready.planId(),
                ready.lastEventSequence() + 1,
                ready.createdAt().plusSeconds(1),
                new EventType("STEP_ACTIVATED"),
                Optional.of(replan.replanEvent().id()),
                "activation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint active = new Checkpoint(
                ready.taskFrameId(), ready.planId(),
                ready.revisionId(), ready.revisionNumber(),
                event.sequence(), PlanExecutionState.ACTIVE,
                states, ready.receiptReferences(),
                ready.createdAt().plusSeconds(1));
        return new StepActivationRequest(
                replan.planId(), leaseToken, replan.fencingToken(),
                replan.replannedRevision().id(),
                replan.replannedRevision().number(),
                replan.replannedCheckpoint().version(),
                ready.lastEventSequence(), stepId, event, active);
    }

    static ActiveStepReplanRequest requestAfter(
            PersistedActiveStepReplan previous,
            PersistedStepActivation activation,
            String leaseToken, String suffix) {
        Checkpoint active =
                activation.activatedCheckpoint().checkpoint();
        PlanRevision source = previous.replannedRevision();
        Map<PlanStepId, StepExecutionState> supersededStates =
                new LinkedHashMap<>(active.stepStates());
        supersededStates.put(
                activation.stepId(),
                StepExecutionState.SUPERSEDED_BY_REPLAN);
        Instant sourceTime = active.createdAt().isAfter(
                source.createdAt())
                ? active.createdAt() : source.createdAt();
        EventEnvelope supersession = new EventEnvelope(
                new EventId("supersession-" + suffix),
                active.taskFrameId(), active.planId(),
                active.lastEventSequence() + 1,
                sourceTime.plusSeconds(1),
                new EventType("STEP_SUPERSEDED_BY_REPLAN"),
                Optional.of(activation.activationEvent().id()),
                "replan-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint supersededCheckpoint = new Checkpoint(
                active.taskFrameId(), active.planId(),
                active.revisionId(), active.revisionNumber(),
                supersession.sequence(), PlanExecutionState.ACTIVE,
                supersededStates, active.receiptReferences(),
                sourceTime.plusSeconds(1));
        PlanStep replacement = new PlanStep(
                new PlanStepId("replacement-" + suffix),
                "Recover with another replacement",
                "Replacement completes",
                Set.of(), List.of("replacement complete"),
                new BoundedExecutionHints(
                        2, Duration.ofMinutes(1)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-"
                        + (source.number() + 1) + "-" + suffix),
                source.taskFrameId(), source.number() + 1,
                Optional.of(source.id()), "bounded replan",
                sourceTime.plusSeconds(2),
                List.of(replacement), source.completedFacts());
        EventEnvelope replan = new EventEnvelope(
                new EventId("replan-" + suffix),
                active.taskFrameId(), active.planId(),
                supersession.sequence() + 1,
                supersession.occurredAt().plusSeconds(1),
                new EventType("PLAN_REPLANNED"),
                Optional.of(supersession.id()),
                "replan-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint replacementCheckpoint = new Checkpoint(
                active.taskFrameId(), active.planId(),
                revision.id(), revision.number(),
                replan.sequence(), PlanExecutionState.ACTIVE,
                Map.of(replacement.id(),
                        StepExecutionState.NOT_STARTED),
                active.receiptReferences(),
                supersededCheckpoint.createdAt().plusSeconds(1));
        return new ActiveStepReplanRequest(
                active.planId(), leaseToken,
                activation.fencingToken(),
                source.id(), source.number(),
                activation.activatedCheckpoint().version(),
                active.lastEventSequence(), activation.stepId(),
                supersession, supersededCheckpoint,
                replan, revision, replacementCheckpoint);
    }
}
