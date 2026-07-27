package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRecoveryRepositoryBoundaryTest {
    private static final String OWNER = "step-recovery-boundary-owner";
    private static final String TOKEN = "step-recovery-boundary-token";

    @Test
    void snapshotRequiresEveryComponentExceptAnExplicitOptionalAndRedactsThem() {
        Harness harness = activeHarness("record");
        PersistedStepRecoveryActive snapshot = (PersistedStepRecoveryActive)
                harness.recovery().inspect(harness.plan().id()).value().orElseThrow();

        assertThrows(NullPointerException.class,
                () -> new PersistedStepRecoveryActive(
                        null,
                        snapshot.plan(),
                        snapshot.checkpoint(),
                        snapshot.activation(),
                        snapshot.executionContext()));
        assertThrows(NullPointerException.class,
                () -> new PersistedStepRecoveryActive(
                        snapshot.taskFrame(),
                        null,
                        snapshot.checkpoint(),
                        snapshot.activation(),
                        snapshot.executionContext()));
        assertThrows(NullPointerException.class,
                () -> new PersistedStepRecoveryActive(
                        snapshot.taskFrame(),
                        snapshot.plan(),
                        null,
                        snapshot.activation(),
                        snapshot.executionContext()));
        assertThrows(NullPointerException.class,
                () -> new PersistedStepRecoveryActive(
                        snapshot.taskFrame(),
                        snapshot.plan(),
                        snapshot.checkpoint(),
                        null,
                        snapshot.executionContext()));
        assertThrows(NullPointerException.class,
                () -> new PersistedStepRecoveryActive(
                        snapshot.taskFrame(),
                        snapshot.plan(),
                        snapshot.checkpoint(),
                        snapshot.activation(),
                        null));
        assertEquals(Optional.empty(), new PersistedStepRecoveryActive(
                snapshot.taskFrame(),
                snapshot.plan(),
                snapshot.checkpoint(),
                snapshot.activation(),
                Optional.empty()).executionContext());

        assertFalse(snapshot.toString().contains(TOKEN));
        assertFalse(PersistenceResult.found(snapshot).toString().contains(TOKEN));
        assertTrue(snapshot.toString().contains("<provided>"));
    }

    @Test
    void missingActivationOrConfirmedContextFailsClosedAsPartialState() {
        Harness marker = activeHarness("marker");
        marker.state().stepActivations.get(marker.plan().id()).remove(
                marker.activation().activationEvent().id());
        assertPartial(marker.recovery().inspect(marker.plan().id()));

        Harness context = activeHarness("context");
        context.state().planExecutionContextConfirmations.remove(
                context.plan().id());
        assertPartial(context.recovery().inspect(context.plan().id()));

        Harness root = activeHarness("root");
        root.state().executionStarts.remove(root.plan().id());
        assertPartial(root.recovery().inspect(root.plan().id()));
    }

    @Test
    void concurrentInspectionSeesOnlyActiveOrPostPauseAtomicCuts()
            throws Exception {
        Harness harness = activeHarness("concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<Boolean> reader = executor.submit(() -> {
                started.countDown();
                for (int i = 0; i < 100; i++) {
                    PersistenceResult<StepRecoverySnapshot> result =
                            harness.recovery().inspect(harness.plan().id());
                    if (result.outcome() == PersistenceOutcome.FOUND) {
                        if (!(result.value().orElseThrow()
                                instanceof PersistedStepRecoveryActive)) {
                            return false;
                        }
                    } else if (!isNotEligible(result)) {
                        return false;
                    }
                }
                return true;
            });
            Future<PersistenceResult<PersistedStepInterruption>> writer =
                    executor.submit(() -> {
                        started.await();
                        return harness.interruptions().pause(
                                pauseRequest(harness, "concurrent"));
                    });

            assertTrue(reader.get());
            assertEquals(PersistenceOutcome.APPLIED, writer.get().outcome());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Harness activeHarness(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
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
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("boundary-" + suffix));
        PersistedStepActivation activation = requireApplied(
                new InMemoryStepActivationRepository(state).activate(
                        PersistenceFixtures.stepActivationRequest(
                                plan,
                                TOKEN,
                                lease.fencingToken(),
                                "activation-" + suffix)));
        return new Harness(
                state,
                plan,
                new InMemoryStepInterruptionRepository(state),
                new InMemoryStepRecoveryRepository(state),
                activation);
    }

    private static StepPauseRequest pauseRequest(Harness harness, String suffix) {
        VersionedCheckpoint source = harness.state().checkpoints.get(
                harness.plan().id());
        Checkpoint current = source.checkpoint();
        long sequence = current.lastEventSequence() + 1;
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                current.stepStates());
        states.put(PersistenceFixtures.STEP_1, StepExecutionState.PAUSED);
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
                        sequence),
                new Checkpoint(
                        current.taskFrameId(),
                        current.planId(),
                        current.revisionId(),
                        current.revisionNumber(),
                        sequence,
                        PlanExecutionState.PAUSED,
                        states,
                        current.receiptReferences(),
                        current.createdAt().plusSeconds(1)));
    }

    private static boolean isNotEligible(PersistenceResult<?> result) {
        return result.outcome() == PersistenceOutcome.REJECTED
                && result.failure().map(PersistenceFailure::code)
                        .filter(code -> code
                                == PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE)
                        .isPresent();
    }

    private static void assertPartial(PersistenceResult<?> result) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        PersistenceFailure failure = result.failure().orElseThrow();
        assertEquals(PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                failure.code());
        assertEquals("stepRecovery", failure.path());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private record Harness(
            InMemoryState state,
            Plan plan,
            InMemoryStepInterruptionRepository interruptions,
            StepRecoveryRepository recovery,
            PersistedStepActivation activation) {
    }
}
