package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
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
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class StepActivationCompositionTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-25T00:00:00Z");

    private StepActivationCompositionTestFixtures() {
    }

    static StepActivationCompositionRequest request(
            PersistedExecutionStartCommitted committed,
            String suffix) {
        PlanStepId stepId = committed.currentPlan().latestRevision().steps().get(0).id();
        return new StepActivationCompositionRequest(
                committed,
                stepId,
                new StepActivationAttempt(
                        "owner-" + suffix,
                        "token-" + suffix,
                        T0.plus(Duration.ofMinutes(5)),
                        draft(suffix),
                        T0.plusSeconds(3)));
    }

    static StepActivationEventDraft draft(String suffix) {
        return new StepActivationEventDraft(
                new EventId("activation-" + suffix),
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of(
                        "value", new TextValue(suffix)))));
    }

    static PersistedExecutionStartCommitted committed(
            String suffix,
            boolean sourceBacked) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        PlanStep step = new PlanStep(
                stepId,
                "do " + suffix,
                "verify " + suffix,
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "goal " + suffix,
                List.of("object"),
                List.of("deliverable"),
                List.of("constraint"),
                sourceBacked
                        ? Optional.of(new ProjectVersionRef("project-" + suffix, "version-" + suffix))
                        : Optional.empty(),
                profile(),
                T0);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial",
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(new io.paperagent.v2.contracts.PlanId("plan-" + suffix), taskFrameId, List.of(revision));
        Checkpoint initial = checkpoint(taskFrame, plan, 0, PlanExecutionState.NOT_STARTED, T0);
        PersistedPlanBootstrap bootstrap = new PersistedPlanBootstrap(
                taskFrame, plan, new VersionedCheckpoint(1, initial));
        var startEvent = new io.paperagent.v2.contracts.EventEnvelope(
                new EventId("start-" + suffix), taskFrameId, plan.id(), 1,
                T0.plusSeconds(1), new EventType("execution-start"), Optional.empty(),
                "start-" + suffix, new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint h0 = checkpoint(taskFrame, plan, 1, PlanExecutionState.ACTIVE, T0.plusSeconds(1));
        return new PersistedExecutionStartCommitted(
                bootstrap,
                plan,
                new io.paperagent.v2.persistence.PersistedExecutionStart(
                        plan.id(), "start-owner-" + suffix, 1, startEvent,
                        new VersionedCheckpoint(2, h0)));
    }

    static Seeded seeded(String suffix, boolean sourceBacked) {
        PersistedExecutionStartCommitted fixture = committed(suffix, sourceBacked);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
        requireApplied(persistence.planBootstraps().bootstrap(
                fixture.bootstrap().taskFrame(), fixture.currentPlan(),
                fixture.bootstrap().initialCheckpoint().checkpoint()));
        String token = "token-" + suffix;
        LeaseRecord lease = requireApplied(persistence.leases().acquire(
                fixture.planId(), "owner-" + suffix, token,
                T0.plus(Duration.ofMinutes(5))));
        requireApplied(persistence.executionStarts().start(new ExecutionStartRequest(
                fixture.planId(), token, lease.fencingToken(),
                fixture.executionStart().startEvent(),
                fixture.executionStart().startedCheckpoint().checkpoint())));
        PersistedExecutionStartCommitted committed = (PersistedExecutionStartCommitted)
                requireFound(persistence.executionStartRecovery().inspect(fixture.planId()));
        return new Seeded(persistence, committed, request(committed, suffix));
    }

    static DefaultStepActivationComposer composer(InMemoryPersistence persistence) {
        return new DefaultStepActivationComposer(
                new DeterministicCommittedStepActivationMaterializer(),
                persistence.leases(),
                persistence.stepActivations());
    }

    private static Checkpoint checkpoint(
            TaskFrame taskFrame,
            Plan plan,
            long sequence,
            PlanExecutionState state,
            Instant createdAt) {
        Map<PlanStepId, StepExecutionState> steps = new LinkedHashMap<>();
        plan.latestRevision().steps().forEach(step ->
                steps.put(step.id(), StepExecutionState.NOT_STARTED));
        return new Checkpoint(taskFrame.id(), plan.id(), plan.latestRevision().id(),
                plan.latestRevision().number(), sequence, state, steps, List.of(), createdAt);
    }

    private static ExecutionProfile profile() {
        return new ExecutionProfile(ExecutionTier.SANDBOX_STANDARD, Set.of(),
                NetworkPolicy.DENY_ALL, List.of(),
                new ResourceLimits(Duration.ofMinutes(5), Duration.ofMinutes(2),
                        1024, 1024, 1), Set.of());
    }

    private static <T> T requireApplied(io.paperagent.v2.persistence.PersistenceResult<T> result) {
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError("expected applied");
        }
        return result.value().orElseThrow();
    }

    private static <T> T requireFound(io.paperagent.v2.persistence.PersistenceResult<T> result) {
        if (result.outcome() != PersistenceOutcome.FOUND) {
            throw new AssertionError("expected found");
        }
        return result.value().orElseThrow();
    }

    record Seeded(
            InMemoryPersistence persistence,
            PersistedExecutionStartCommitted committed,
            StepActivationCompositionRequest request) {
    }
}
