package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRecoveryRepositoryTest {
    private static final String OWNER = "step-recovery-owner";
    private static final String TOKEN = "step-recovery-token";

    @Test
    void projectBackedActiveStepReturnsOneConfirmedRecoverySnapshot() {
        Harness harness = activeHarness("project", PersistenceFixtures.taskFrame());

        PersistenceResult<StepRecoverySnapshot> result = harness.recovery()
                .inspect(harness.plan().id());

        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        PersistedStepRecoveryActive snapshot =
                (PersistedStepRecoveryActive) result.value().orElseThrow();
        assertEquals(harness.plan().id(), snapshot.planId());
        assertSame(harness.taskFrame(), snapshot.taskFrame());
        assertSame(harness.state().plans.get(harness.plan().id()), snapshot.plan());
        assertSame(harness.state().checkpoints.get(harness.plan().id()),
                snapshot.checkpoint());
        assertSame(harness.activation(), snapshot.activation());
        assertEquals(Optional.of(harness.context()), snapshot.executionContext());
    }

    @Test
    void sourceLessActiveStepReturnsEmptyContext() {
        TaskFrame sourceLess = PersistenceFixtures.sourceLessTaskFrame(
                PersistenceFixtures.TASK_ID, "source-less recovery");
        Harness harness = activeHarness("source-less", sourceLess);

        PersistenceResult<StepRecoverySnapshot> result = harness.recovery()
                .inspect(harness.plan().id());

        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        PersistedStepRecoveryActive snapshot =
                (PersistedStepRecoveryActive) result.value().orElseThrow();
        assertEquals(Optional.empty(), snapshot.executionContext());
        assertEquals(sourceLess, snapshot.taskFrame());
    }

    @Test
    void invalidMissingAndStartedPlanHaveStableClassifications() {
        InMemoryState empty = new InMemoryState(
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0));
        StepRecoveryRepository recovery = new InMemoryStepRecoveryRepository(empty);
        assertFailure(recovery.inspect(null), PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(recovery.inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.NOT_FOUND, "planId");

        Harness started = startedHarness("started-only", PersistenceFixtures.taskFrame());
        PersistenceResult<StepRecoverySnapshot> ready =
                started.recovery().inspect(started.plan().id());
        assertEquals(PersistenceOutcome.FOUND, ready.outcome());
        assertEquals(
                PersistenceFixtures.STEP_1,
                ((PersistedStepRecoveryReady) ready.value().orElseThrow())
                        .readyStepId());
    }

    @Test
    void interruptionAndCompletionSupersedeActiveRecoveryEligibility() {
        Harness paused = activeHarness("pause", PersistenceFixtures.taskFrame());
        requireApplied(paused.interruptions().pause(pauseRequest(paused, "pause")));
        assertFailure(paused.recovery().inspect(paused.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE, "stepRecovery");

        Harness completed = activeHarness("complete", PersistenceFixtures.taskFrame());
        requireApplied(completed.completions().complete(
                completionRequest(completed, "complete")));
        PersistenceResult<StepRecoverySnapshot> ready =
                completed.recovery().inspect(completed.plan().id());
        assertEquals(PersistenceOutcome.FOUND, ready.outcome());
        assertTrue(ready.value().orElseThrow()
                instanceof PersistedStepRecoveryReady);
    }

    @Test
    void inspectionIgnoresLeaseTurnoverClockAndBusinessWrites() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness harness = activeHarness("clock", PersistenceFixtures.taskFrame(), clock);
        AuthorityFootprint before = AuthorityFootprint.capture(harness.state());
        clock.set(PersistenceFixtures.T0.plus(Duration.ofMinutes(2)));
        requireApplied(harness.leases().acquire(
                harness.plan().id(), "takeover", "takeover-token",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(3))));
        AuthorityFootprint afterTakeover = AuthorityFootprint.capture(harness.state());
        int observations = clock.observationCount();
        clock.failOnObservation();

        PersistenceResult<StepRecoverySnapshot> result = harness.recovery()
                .inspect(harness.plan().id());

        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        assertEquals(observations, clock.observationCount());
        assertEquals(afterTakeover, AuthorityFootprint.capture(harness.state()));
        assertTrue(!before.equals(afterTakeover));
    }

    private static Harness activeHarness(String suffix, TaskFrame taskFrame) {
        return activeHarness(
                suffix,
                taskFrame,
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0));
    }

    private static Harness activeHarness(
            String suffix,
            TaskFrame taskFrame,
            PersistenceFixtures.MutableCountingClock clock) {
        Harness harness = startedHarness(suffix, taskFrame, clock);
        PersistedStepActivation activation = requireApplied(
                harness.activations().activate(PersistenceFixtures.stepActivationRequest(
                        harness.plan(), TOKEN, 1, "activation-" + suffix)));
        return harness.withActivation(activation);
    }

    private static Harness startedHarness(String suffix, TaskFrame taskFrame) {
        return startedHarness(
                suffix,
                taskFrame,
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0));
    }

    private static Harness startedHarness(
            String suffix,
            TaskFrame taskFrame,
            PersistenceFixtures.MutableCountingClock clock) {
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                taskFrame, plan, PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(), OWNER, TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-" + suffix)));
        PersistedPlanExecutionContextConfirmed context = null;
        if (taskFrame.sourceProjectVersion().isPresent()) {
            context = PersistenceFixtures.confirmExecutionContext(
                    new InMemoryPlanExecutionContextRepository(state),
                    plan,
                    TOKEN,
                    lease.fencingToken(),
                    PersistenceFixtures.workspaceSpec("recovery-" + suffix));
        }
        return new Harness(
                state,
                clock,
                taskFrame,
                plan,
                leases,
                new InMemoryStepActivationRepository(state),
                new InMemoryStepCompletionRepository(state),
                new InMemoryStepInterruptionRepository(state),
                new InMemoryStepRecoveryRepository(state),
                context,
                null);
    }

    private static StepPauseRequest pauseRequest(Harness harness, String suffix) {
        VersionedCheckpoint source = harness.state().checkpoints.get(
                harness.plan().id());
        Checkpoint current = source.checkpoint();
        long eventSequence = current.lastEventSequence() + 1;
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                current.stepStates());
        states.put(PersistenceFixtures.STEP_1, StepExecutionState.PAUSED);
        Checkpoint target = new Checkpoint(
                current.taskFrameId(),
                current.planId(),
                current.revisionId(),
                current.revisionNumber(),
                eventSequence,
                PlanExecutionState.PAUSED,
                states,
                current.receiptReferences(),
                current.createdAt().plusSeconds(1));
        Plan plan = harness.state().plans.get(harness.plan().id());
        return new StepPauseRequest(
                plan.id(),
                TOKEN,
                1,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                source.version(),
                current.lastEventSequence(),
                PersistenceFixtures.STEP_1,
                PersistenceFixtures.event(
                        "pause-" + suffix,
                        plan.taskFrameId(),
                        plan.id(),
                        eventSequence),
                target);
    }

    private static StepCompletionRequest completionRequest(
            Harness harness,
            String suffix) {
        Plan plan = harness.state().plans.get(harness.plan().id());
        VersionedCheckpoint source = harness.state().checkpoints.get(plan.id());
        Checkpoint current = source.checkpoint();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "completed-" + suffix,
                current.createdAt().plusSeconds(1),
                List.of());
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>(
                plan.latestRevision().completedFacts());
        facts.put(PersistenceFixtures.STEP_1, fact);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("recovery-completion-" + suffix),
                plan.taskFrameId(),
                plan.latestRevision().number() + 1,
                Optional.of(plan.latestRevision().id()),
                "complete active step",
                current.createdAt().plusSeconds(1),
                plan.latestRevision().steps(),
                facts);
        long eventSequence = current.lastEventSequence() + 1;
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                current.stepStates());
        states.put(PersistenceFixtures.STEP_1, StepExecutionState.SUCCEEDED);
        Checkpoint target = new Checkpoint(
                current.taskFrameId(),
                current.planId(),
                revision.id(),
                revision.number(),
                eventSequence,
                PlanExecutionState.ACTIVE,
                states,
                current.receiptReferences(),
                current.createdAt().plusSeconds(1));
        return new StepCompletionRequest(
                plan.id(),
                TOKEN,
                1,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                source.version(),
                current.lastEventSequence(),
                PersistenceFixtures.STEP_1,
                fact,
                PersistenceFixtures.event(
                        "complete-" + suffix,
                        plan.taskFrameId(),
                        plan.id(),
                        eventSequence),
                revision,
                target);
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        PersistenceFailure failure = result.failure().orElseThrow();
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }

    private record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            TaskFrame taskFrame,
            Plan plan,
            InMemoryLeaseRepository leases,
            InMemoryStepActivationRepository activations,
            InMemoryStepCompletionRepository completions,
            InMemoryStepInterruptionRepository interruptions,
            StepRecoveryRepository recovery,
            PersistedPlanExecutionContextConfirmed context,
            PersistedStepActivation activation) {

        Harness withActivation(PersistedStepActivation value) {
            return new Harness(
                    state,
                    clock,
                    taskFrame,
                    plan,
                    leases,
                    activations,
                    completions,
                    interruptions,
                    recovery,
                    context,
                    value);
        }
    }

    private record AuthorityFootprint(
            Map<?, ?> taskFrames,
            Map<?, ?> plans,
            Map<?, ?> events,
            Map<?, ?> streams,
            Map<?, ?> checkpoints,
            Map<?, ?> starts,
            Map<?, ?> heads,
            Map<?, ?> links,
            Map<?, ?> activations,
            Map<?, ?> completions,
            Map<?, ?> pauses,
            Map<?, ?> contexts,
            Map<?, ?> confirmations,
            Map<?, ?> owners,
            Map<?, ?> leases,
            Map<?, ?> fencingTokens,
            Object leaseTimeHighWater) {

        static AuthorityFootprint capture(InMemoryState state) {
            return new AuthorityFootprint(
                    Map.copyOf(state.taskFrames),
                    Map.copyOf(state.plans),
                    Map.copyOf(state.eventsById),
                    Map.copyOf(state.eventStreams),
                    Map.copyOf(state.checkpoints),
                    Map.copyOf(state.executionStarts),
                    Map.copyOf(state.executionMutationHeads),
                    Map.copyOf(state.executionMutationLinks),
                    Map.copyOf(state.stepActivations),
                    Map.copyOf(state.stepCompletions),
                    Map.copyOf(state.stepPauses),
                    Map.copyOf(state.planExecutionContextReservations),
                    Map.copyOf(state.planExecutionContextConfirmations),
                    Map.copyOf(state.workspaceOwners),
                    Map.copyOf(state.leases),
                    Map.copyOf(state.fencingTokens),
                    state.leaseTimeHighWater);
        }
    }
}
