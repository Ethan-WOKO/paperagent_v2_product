package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StepInterruptionRepositoryTest {
    private static final String OWNER = "worker-a";
    private static final String TOKEN = "lease-token-a";

    @Test
    void pauseFailAndCancelAtomicallyRecordOnlyTheirFixedInterruptionFact() {
        assertFirstWrite(StepInterruptionKind.PAUSE, "pause-first");
        assertFirstWrite(StepInterruptionKind.FAIL, "fail-first");
        assertFirstWrite(StepInterruptionKind.CANCEL, "cancel-first");
    }

    @Test
    void exactReplaySurvivesLeaseTakeoverAndMutableSourceRemovalWithoutClockAccess() {
        Harness harness = active("replay");
        StepPauseRequest request = pauseRequest(harness, "pause-replay");
        PersistedStepInterruption original = requireApplied(
                harness.interruptions().pause(request));

        harness.clock().set(PersistenceFixtures.T0.plusSeconds(70));
        requireApplied(harness.leases().acquire(
                harness.plan().id(),
                "takeover-owner",
                "takeover-token",
                PersistenceFixtures.T0.plusSeconds(120)));
        harness.state().plans.remove(harness.plan().id());
        int before = harness.clock().observationCount();
        harness.clock().failOnObservation();

        PersistenceResult<PersistedStepInterruption> replayed =
                harness.interruptions().pause(request);

        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome(), replayed.toString());
        assertSame(original, replayed.value().orElseThrow());
        assertEquals(before, harness.clock().observationCount());
    }

    @Test
    void exactReplayAfterPreStartRevisionSurvivesPlanRemovalWithoutClockAccess() {
        Harness harness = activeAfterPreStartRevision("replay-pre-start-revision");
        StepPauseRequest request = pauseRequest(
                harness, "pause-replay-pre-start-revision");
        PersistedStepInterruption original = requireApplied(
                harness.interruptions().pause(request));

        harness.state().plans.remove(harness.plan().id());
        int before = harness.clock().observationCount();
        harness.clock().failOnObservation();

        PersistenceResult<PersistedStepInterruption> replayed =
                harness.interruptions().pause(request);

        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome(), replayed.toString());
        assertSame(original, replayed.value().orElseThrow());
        assertEquals(before, harness.clock().observationCount());
    }

    @Test
    void changedAndCrossKindSameIdentityConflictWithoutClockAccess() {
        Harness harness = active("identity");
        StepPauseRequest pause = pauseRequest(harness, "shared-interruption-id");
        requireApplied(harness.interruptions().pause(pause));

        StepPauseRequest changed = new StepPauseRequest(
                pause.planId(),
                "changed-token",
                pause.fencingToken(),
                pause.expectedRevisionId(),
                pause.expectedRevisionNumber(),
                pause.expectedCheckpointVersion(),
                pause.expectedEventHeadSequence(),
                pause.stepId(),
                pause.pauseEvent(),
                pause.pausedCheckpoint());
        StepFailRequest failure = failRequest(
                harness, pause.pauseEvent().id().value());
        int before = harness.clock().observationCount();
        harness.clock().failOnObservation();

        assertFailure(
                harness.interruptions().pause(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.pauseEvent.id");
        assertFailure(
                harness.interruptions().fail(failure),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.failureEvent.id");
        assertEquals(before, harness.clock().observationCount());
    }

    @Test
    void terminalInterruptionSourceIsNotEligibleForAnotherInterruption() {
        Harness harness = active("terminal");
        requireApplied(harness.interruptions().cancel(
                cancelRequest(harness, "cancel-terminal")));

        assertFailure(
                harness.interruptions().pause(pauseRequest(harness, "pause-after-cancel")),
                PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                "stepInterruption");
    }

    private static void assertFirstWrite(
            StepInterruptionKind kind,
            String suffix) {
        Harness harness = active(suffix);
        UnchangedState before = unchangedState(harness.state());
        PersistedStepInterruption result = requireApplied(invoke(
                harness.interruptions(), kind, interruptionRequest(harness, kind, suffix)));

        Checkpoint checkpoint = result.interruptedCheckpoint().checkpoint();
        assertEquals(kind, result.kind());
        assertEquals(harness.plan().id(), result.planId());
        assertEquals(PersistenceFixtures.STEP_1, result.stepId());
        assertEquals(4, result.interruptedCheckpoint().version());
        assertEquals(stepState(kind), checkpoint.stepStates().get(PersistenceFixtures.STEP_1));
        assertEquals(planState(kind), checkpoint.planState());
        assertEquals(StepExecutionState.NOT_STARTED,
                checkpoint.stepStates().get(PersistenceFixtures.STEP_2));
        assertEquals(3, harness.state().eventStreams.get(harness.plan().id()).size());
        assertEquals(2, harness.state().executionMutationLinks.get(harness.plan().id()).size());
        assertEquals(result.interruptedCheckpoint(),
                harness.state().checkpoints.get(harness.plan().id()));
        assertEquals(before, unchangedState(harness.state()));
        assertMarkerWritten(harness, kind, result.interruptionEvent().id());
    }

    private static void assertMarkerWritten(
            Harness harness,
            StepInterruptionKind kind,
            EventId eventId) {
        int pauses = harness.state().stepPauses.get(harness.plan().id()).size();
        int failures = harness.state().stepFailures.get(harness.plan().id()).size();
        int cancellations = harness.state().stepCancellations.get(harness.plan().id()).size();
        assertEquals(kind == StepInterruptionKind.PAUSE ? 1 : 0, pauses);
        assertEquals(kind == StepInterruptionKind.FAIL ? 1 : 0, failures);
        assertEquals(kind == StepInterruptionKind.CANCEL ? 1 : 0, cancellations);
        assertEquals(eventId,
                harness.state().executionMutationHeads.get(harness.plan().id())
                        .mutationEventId());
    }

    private static PersistenceResult<PersistedStepInterruption> invoke(
            StepInterruptionRepository repository,
            StepInterruptionKind kind,
            Object request) {
        return switch (kind) {
            case PAUSE -> repository.pause((StepPauseRequest) request);
            case FAIL -> repository.fail((StepFailRequest) request);
            case CANCEL -> repository.cancel((StepCancelRequest) request);
        };
    }

    static Object interruptionRequest(
            Harness harness,
            StepInterruptionKind kind,
            String eventId) {
        Plan plan = harness.state().plans.get(harness.plan().id());
        VersionedCheckpoint source = harness.state().checkpoints.get(plan.id());
        Checkpoint current = source.checkpoint();
        long eventSequence = current.lastEventSequence() + 1;
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                current.stepStates());
        states.put(PersistenceFixtures.STEP_1, stepState(kind));
        Checkpoint target = new Checkpoint(
                current.taskFrameId(),
                current.planId(),
                current.revisionId(),
                current.revisionNumber(),
                eventSequence,
                planState(kind),
                states,
                current.receiptReferences(),
                current.createdAt().plusSeconds(1));
        return switch (kind) {
            case PAUSE -> new StepPauseRequest(
                    plan.id(), TOKEN, 1, plan.latestRevision().id(),
                    plan.latestRevision().number(), source.version(),
                    current.lastEventSequence(), PersistenceFixtures.STEP_1,
                    PersistenceFixtures.event(
                            eventId, plan.taskFrameId(), plan.id(), eventSequence),
                    target);
            case FAIL -> new StepFailRequest(
                    plan.id(), TOKEN, 1, plan.latestRevision().id(),
                    plan.latestRevision().number(), source.version(),
                    current.lastEventSequence(), PersistenceFixtures.STEP_1,
                    PersistenceFixtures.event(
                            eventId, plan.taskFrameId(), plan.id(), eventSequence),
                    target);
            case CANCEL -> new StepCancelRequest(
                    plan.id(), TOKEN, 1, plan.latestRevision().id(),
                    plan.latestRevision().number(), source.version(),
                    current.lastEventSequence(), PersistenceFixtures.STEP_1,
                    PersistenceFixtures.event(
                            eventId, plan.taskFrameId(), plan.id(), eventSequence),
                    target);
        };
    }

    static StepPauseRequest pauseRequest(Harness harness, String eventId) {
        return (StepPauseRequest) interruptionRequest(
                harness, StepInterruptionKind.PAUSE, eventId);
    }

    static StepFailRequest failRequest(Harness harness, String eventId) {
        return (StepFailRequest) interruptionRequest(
                harness, StepInterruptionKind.FAIL, eventId);
    }

    static StepCancelRequest cancelRequest(Harness harness, String eventId) {
        return (StepCancelRequest) interruptionRequest(
                harness, StepInterruptionKind.CANCEL, eventId);
    }

    static Harness active(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(), plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(), OWNER, TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("interruption-" + suffix));
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(), "activation-" + suffix)));
        return new Harness(
                state,
                clock,
                plan,
                leases,
                new InMemoryStepInterruptionRepository(state));
    }

    private static Harness activeAfterPreStartRevision(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan initialPlan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                initialPlan,
                PersistenceFixtures.initialCheckpoint(initialPlan)));
        Plan plan = requireApplied(new InMemoryPlanRepository(state).appendRevision(
                initialPlan.id(),
                initialPlan.latestRevision().number(),
                PersistenceFixtures.revision2(
                        "revision-" + suffix, "pre-start revision")));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(), OWNER, TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("interruption-" + suffix));
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(),
                        "activation-" + suffix)));
        return new Harness(
                state,
                clock,
                plan,
                leases,
                new InMemoryStepInterruptionRepository(state));
    }

    private static UnchangedState unchangedState(InMemoryState state) {
        return new UnchangedState(
                Map.copyOf(state.plans),
                Map.copyOf(state.receipts),
                Map.copyOf(state.stepActivations),
                Map.copyOf(state.stepCompletions),
                Map.copyOf(state.effectIntents),
                Map.copyOf(state.effectProgresses),
                Map.copyOf(state.effectResults),
                Map.copyOf(state.planExecutionContextReservations),
                Map.copyOf(state.planExecutionContextConfirmations),
                Map.copyOf(state.workspaceOwners),
                Map.copyOf(state.leases),
                Map.copyOf(state.idempotency));
    }

    static StepExecutionState stepState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    static PlanExecutionState planState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    static record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            InMemoryLeaseRepository leases,
            StepInterruptionRepository interruptions) {
    }

    private record UnchangedState(
            Map<?, ?> plans,
            Map<?, ?> receipts,
            Map<?, ?> activations,
            Map<?, ?> completions,
            Map<?, ?> effectIntents,
            Map<?, ?> effectProgresses,
            Map<?, ?> effectResults,
            Map<?, ?> contextReservations,
            Map<?, ?> contextConfirmations,
            Map<?, ?> workspaceOwners,
            Map<?, ?> leases,
            Map<?, ?> idempotency) {
    }
}
