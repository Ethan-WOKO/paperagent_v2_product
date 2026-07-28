package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CommittedStepActivationMaterializationFixture {
    static final Instant T0 = Instant.parse("2026-07-25T00:00:00Z");

    private CommittedStepActivationMaterializationFixture() {
    }

    static PersistedExecutionStartCommitted committed(String suffix) {
        return committed(suffix, false);
    }

    static PersistedExecutionStartCommitted committed(
            String suffix,
            boolean sourceBacked) {
        return committed(suffix, sourceBacked, false, false);
    }

    static PersistedExecutionStartCommitted singleRootCommitted(
            String suffix) {
        return committed(suffix, false, true, false);
    }

    static PersistedExecutionStartCommitted
            committedWithNonCanonicalNestedInitial(String suffix) {
        return committed(suffix, false, false, true);
    }

    private static PersistedExecutionStartCommitted committed(
            String suffix,
            boolean sourceBacked,
            boolean singleRoot,
            boolean nonCanonicalNestedInitial) {
        TaskFrame taskFrame = taskFrame(suffix, sourceBacked);
        PlanStep first = rootStep("step-first-" + suffix);
        PlanStep second = rootStep("step-second-" + suffix);
        PlanStep dependent = new PlanStep(
                new PlanStepId("step-dependent-" + suffix),
                "Perform dependent work",
                "Dependent work is verified",
                Set.of(first.id()),
                List.of("dependent result is verified"),
                hints());
        List<PlanStep> steps = singleRoot
                ? List.of(first)
                : List.of(first, second, dependent);
        Plan plan = plan(
                suffix,
                taskFrame.id(),
                steps);
        Checkpoint initial = checkpoint(
                taskFrame,
                plan,
                nonCanonicalNestedInitial ? 99 : 0,
                nonCanonicalNestedInitial
                        ? PlanExecutionState.SUCCEEDED
                        : PlanExecutionState.NOT_STARTED,
                allStates(plan, StepExecutionState.NOT_STARTED),
                T0);
        PersistedPlanBootstrap bootstrap = new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, initial));
        EventEnvelope startEvent = new EventEnvelope(
                new EventId("event-start-" + suffix),
                taskFrame.id(),
                plan.id(),
                1,
                T0.plusSeconds(1),
                new EventType("execution-start"),
                Optional.empty(),
                "correlation-start-" + suffix,
                payload("start-" + suffix));
        Checkpoint h0 = checkpoint(
                taskFrame,
                plan,
                1,
                PlanExecutionState.ACTIVE,
                allStates(plan, StepExecutionState.NOT_STARTED),
                T0.plusSeconds(1));
        PersistedExecutionStart executionStart =
                new PersistedExecutionStart(
                        plan.id(),
                        "owner-" + suffix,
                        1,
                        startEvent,
                        new VersionedCheckpoint(2, h0));
        return new PersistedExecutionStartCommitted(
                bootstrap,
                plan,
                executionStart);
    }

    static CommittedStepActivationMaterializationRequest request(
            String suffix) {
        PersistedExecutionStartCommitted committed = committed(suffix);
        PlanStepId selected = committed.currentPlan()
                .latestRevision()
                .steps()
                .get(1)
                .id();
        return new CommittedStepActivationMaterializationRequest(
                committed,
                selected,
                eventDraft(suffix),
                T0.plusSeconds(2));
    }

    static StepActivationEventDraft eventDraft(String suffix) {
        return new StepActivationEventDraft(
                new EventId("event-activation-" + suffix),
                T0.plusSeconds(2),
                new EventType("custom-activation-" + suffix),
                Optional.empty(),
                "correlation-activation-" + suffix,
                payload("activation-" + suffix));
    }

    static PersistedStepRecoveryReady laterReady(String suffix) {
        PersistedExecutionStartCommitted committed = committed(suffix);
        Plan initialPlan = committed.currentPlan();
        PlanRevision first = initialPlan.latestRevision();
        PlanStepId completedStep = first.steps().get(0).id();
        PlanStepId readyStep = first.steps().get(1).id();
        CompletionFact fact = new CompletionFact(
                completedStep, "outcome-" + suffix, T0.plusSeconds(2),
                List.of());
        PlanRevision second = new PlanRevision(
                new PlanRevisionId("revision-after-first-" + suffix),
                first.taskFrameId(), 2, Optional.of(first.id()),
                "complete first", T0.plusSeconds(2), first.steps(),
                Map.of(completedStep, fact));
        Plan plan = new Plan(
                initialPlan.id(), initialPlan.taskFrameId(),
                List.of(first, second));
        Map<PlanStepId, StepExecutionState> states =
                allStates(plan, StepExecutionState.NOT_STARTED);
        states.put(completedStep, StepExecutionState.SUCCEEDED);
        Checkpoint checkpoint = new Checkpoint(
                committed.bootstrap().taskFrame().id(), plan.id(),
                second.id(), second.number(), 3,
                PlanExecutionState.ACTIVE, states, List.of(),
                T0.plusSeconds(2));
        return new PersistedStepRecoveryReady(
                committed.bootstrap().taskFrame(), plan,
                new VersionedCheckpoint(4, checkpoint),
                readyStep, Optional.empty());
    }

    static MaterializedStepActivation materialized(String suffix) {
        CommittedStepActivationMaterializationRequest request =
                request(suffix);
        PersistedExecutionStartCommitted committed =
                request.committedStart();
        Checkpoint h0 = committed.executionStart()
                .startedCheckpoint()
                .checkpoint();
        EventEnvelope event = new EventEnvelope(
                request.eventDraft().id(),
                committed.bootstrap().taskFrame().id(),
                committed.currentPlan().id(),
                2,
                request.eventDraft().occurredAt(),
                request.eventDraft().type(),
                request.eventDraft().causationId(),
                request.eventDraft().correlationId(),
                request.eventDraft().payload());
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(h0.stepStates());
        states.put(request.stepId(), StepExecutionState.ACTIVE);
        Checkpoint activated = new Checkpoint(
                h0.taskFrameId(),
                h0.planId(),
                h0.revisionId(),
                h0.revisionNumber(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                h0.receiptReferences(),
                request.checkpointCreatedAt());
        return new MaterializedStepActivation(event, activated);
    }

    private static TaskFrame taskFrame(
            String suffix,
            boolean sourceBacked) {
        return new TaskFrame(
                new TaskFrameId("task-" + suffix),
                "Prepare a verified result",
                List.of("paper"),
                List.of("verified result"),
                List.of("preserve authoritative facts"),
                sourceBacked
                        ? Optional.of(new ProjectVersionRef(
                                "project-" + suffix,
                                "version-" + suffix))
                        : Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(
                                Capability.READ_PROJECT,
                                Capability.WRITE_WORKSPACE),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(5),
                                512 * 1024 * 1024L,
                                1024 * 1024L,
                                8),
                        Set.of()),
                T0);
    }

    private static Plan plan(
            String suffix,
            TaskFrameId taskFrameId,
            List<PlanStep> steps) {
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial plan",
                T0,
                steps,
                Map.of());
        return new Plan(
                new PlanId("plan-" + suffix),
                taskFrameId,
                List.of(revision));
    }

    private static PlanStep rootStep(String id) {
        return new PlanStep(
                new PlanStepId(id),
                "Perform " + id,
                "Verify " + id,
                Set.of(),
                List.of("result is verified"),
                hints());
    }

    private static BoundedExecutionHints hints() {
        return new BoundedExecutionHints(3, Duration.ofMinutes(2));
    }

    private static Map<PlanStepId, StepExecutionState> allStates(
            Plan plan,
            StepExecutionState state) {
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>();
        plan.latestRevision().steps().forEach(
                step -> states.put(step.id(), state));
        return states;
    }

    private static Checkpoint checkpoint(
            TaskFrame taskFrame,
            Plan plan,
            long eventSequence,
            PlanExecutionState planState,
            Map<PlanStepId, StepExecutionState> states,
            Instant createdAt) {
        return new Checkpoint(
                taskFrame.id(),
                plan.id(),
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                eventSequence,
                planState,
                states,
                List.of(),
                createdAt);
    }

    private static InlineEventPayload payload(String value) {
        return new InlineEventPayload(new ObjectValue(Map.of(
                "message",
                new TextValue(value))));
    }
}
