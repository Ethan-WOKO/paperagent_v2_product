package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepActivationRepositoryConcurrencyTest {
    private static final String OWNER = "worker-a";
    private static final String TOKEN = "lease-token-a";

    @Test
    void identicalRequestsCommitExactlyOnceAndReplayTheOriginalFact() throws Exception {
        Scenario scenario = scenario();
        List<Callable<PersistenceResult<PersistedStepActivation>>> calls =
                new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            calls.add(() -> scenario.persistence().stepActivations()
                    .activate(scenario.request()));
        }

        List<PersistenceResult<PersistedStepActivation>> results = race(calls);

        assertEquals(
                1,
                results.stream()
                        .filter(result ->
                                result.outcome() == PersistenceOutcome.APPLIED)
                        .count());
        assertEquals(
                15,
                results.stream()
                        .filter(result ->
                                result.outcome() == PersistenceOutcome.REPLAYED)
                        .count());
        assertEquals(
                1,
                results.stream()
                        .map(result -> result.value().orElseThrow())
                        .distinct()
                        .count());
        assertEquals(
                2,
                scenario.persistence().events()
                        .readAfter(PersistenceFixtures.PLAN_ID, 0)
                        .value().orElseThrow().size());
    }

    @Test
    void differentActivationIdentitiesFromSameSourceCannotBothCommit()
            throws Exception {
        Scenario scenario = scenario();
        StepActivationRequest other = withEventIdentity(
                scenario.request(), "activation-other", 4);

        List<PersistenceResult<PersistedStepActivation>> results = race(List.of(
                () -> scenario.persistence().stepActivations()
                        .activate(scenario.request()),
                () -> scenario.persistence().stepActivations()
                        .activate(other)));

        assertEquals(
                1,
                results.stream()
                        .filter(result ->
                                result.outcome() == PersistenceOutcome.APPLIED)
                        .count());
        PersistenceResult<PersistedStepActivation> rejected = results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(
                PersistenceErrorCode.STALE_VERSION,
                rejected.failure().orElseThrow().code());
        assertEquals(
                "request.expectedCheckpointVersion",
                rejected.failure().orElseThrow().path());
        assertEquals(
                1,
                scenario.persistence().checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value().orElseThrow()
                        .checkpoint().stepStates().values().stream()
                        .filter(value -> value
                                == io.paperagent.v2.contracts.StepExecutionState.ACTIVE)
                        .count());
    }

    @Test
    void activationAndRecoveryInspectionLinearizeAsCommittedOrAdvanced()
            throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            Scenario scenario = scenario();
            List<Object> results = raceObjects(List.of(
                    () -> scenario.persistence().stepActivations()
                            .activate(scenario.request()),
                    () -> scenario.persistence().executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID)));
            @SuppressWarnings("unchecked")
            PersistenceResult<ExecutionStartRecoverySnapshot> inspection =
                    (PersistenceResult<ExecutionStartRecoverySnapshot>) results.get(1);
            if (inspection.outcome() == PersistenceOutcome.FOUND) {
                assertTrue(inspection.value().orElseThrow()
                        instanceof PersistedExecutionStartCommitted);
            } else {
                assertEquals(
                        PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                        inspection.failure().orElseThrow().code());
            }
            assertNotEquals(
                    PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                    inspection.failure().map(PersistenceFailure::code)
                            .orElse(null));
        }
    }

    @Test
    void activationAndLeaseReleaseAreStrictlySerialized() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            Scenario scenario = scenario();
            List<Object> results = raceObjects(List.of(
                    () -> scenario.persistence().stepActivations()
                            .activate(scenario.request()),
                    () -> scenario.persistence().leases().release(
                            PersistenceFixtures.PLAN_ID, TOKEN)));
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedStepActivation> activation =
                    (PersistenceResult<PersistedStepActivation>) results.get(0);
            @SuppressWarnings("unchecked")
            PersistenceResult<LeaseRecord> release =
                    (PersistenceResult<LeaseRecord>) results.get(1);
            assertEquals(PersistenceOutcome.APPLIED, release.outcome());
            if (activation.outcome() == PersistenceOutcome.REJECTED) {
                assertEquals(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        activation.failure().orElseThrow().code());
                assertEquals(
                        2,
                        scenario.persistence().checkpoints()
                                .find(PersistenceFixtures.PLAN_ID)
                                .value().orElseThrow().version());
            } else {
                assertEquals(PersistenceOutcome.APPLIED, activation.outcome());
                assertEquals(
                        3,
                        scenario.persistence().checkpoints()
                                .find(PersistenceFixtures.PLAN_ID)
                                .value().orElseThrow().version());
            }
        }
    }

    @Test
    void activationAndRenewBothCommitWithoutChangingFence() throws Exception {
        Scenario scenario = scenario();
        List<Object> results = raceObjects(List.of(
                () -> scenario.persistence().stepActivations()
                        .activate(scenario.request()),
                () -> scenario.persistence().leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        TOKEN,
                        PersistenceFixtures.T0.plus(Duration.ofMinutes(2)))));
        @SuppressWarnings("unchecked")
        PersistenceResult<PersistedStepActivation> activation =
                (PersistenceResult<PersistedStepActivation>) results.get(0);
        @SuppressWarnings("unchecked")
        PersistenceResult<LeaseRecord> renewal =
                (PersistenceResult<LeaseRecord>) results.get(1);
        assertEquals(PersistenceOutcome.APPLIED, activation.outcome());
        assertEquals(PersistenceOutcome.APPLIED, renewal.outcome());
        assertEquals(
                activation.value().orElseThrow().fencingToken(),
                renewal.value().orElseThrow().fencingToken());
    }

    @Test
    void expiryTakeoverRaceNeverLetsOldFenceCommit() throws Exception {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryPersistence persistence =
                PersistenceFixtures.bootstrappedPersistence(clock);
        LeaseRecord lease = requireApplied(persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plusSeconds(30)));
        requireApplied(persistence.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        lease.fencingToken(),
                        "start-expiry-race")));
        confirmContext(
                persistence,
                PersistenceFixtures.plan(),
                TOKEN,
                lease.fencingToken(),
                "expiry-race");
        StepActivationRequest oldRequest =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        lease.fencingToken(),
                        "activation-expiry-race");
        clock.set(PersistenceFixtures.T0.plusSeconds(30));

        List<Object> results = raceObjects(List.of(
                () -> persistence.stepActivations().activate(oldRequest),
                () -> persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        PersistenceFixtures.T0.plus(Duration.ofMinutes(2)))));
        @SuppressWarnings("unchecked")
        PersistenceResult<PersistedStepActivation> activation =
                (PersistenceResult<PersistedStepActivation>) results.get(0);
        @SuppressWarnings("unchecked")
        PersistenceResult<LeaseRecord> takeover =
                (PersistenceResult<LeaseRecord>) results.get(1);
        assertEquals(PersistenceOutcome.REJECTED, activation.outcome());
        assertTrue(
                activation.failure().orElseThrow().code()
                                == PersistenceErrorCode.LEASE_EXPIRED
                        || activation.failure().orElseThrow().code()
                                == PersistenceErrorCode.LEASE_TOKEN_INVALID);
        assertEquals(PersistenceOutcome.APPLIED, takeover.outcome());
        assertEquals(2, takeover.value().orElseThrow().fencingToken());
        assertEquals(
                2,
                persistence.checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value().orElseThrow().version());
    }

    @Test
    void activationAndOrdinaryMutationsShareOneGuardedCut()
            throws Exception {
        Scenario scenario = scenario();
        List<Object> results = raceObjects(List.of(
                () -> scenario.persistence().stepActivations()
                        .activate(scenario.request()),
                () -> scenario.persistence().events().append(
                        PersistenceFixtures.event("ordinary-race-event", 7)),
                () -> scenario.persistence().plans().appendRevision(
                        PersistenceFixtures.PLAN_ID,
                        1,
                        PersistenceFixtures.revision2(
                                "ordinary-race-revision", "guarded race")),
                () -> scenario.persistence().checkpoints().save(
                        2,
                        PersistenceFixtures.startedCheckpoint(
                                PersistenceFixtures.plan()))));

        @SuppressWarnings("unchecked")
        PersistenceResult<PersistedStepActivation> activation =
                (PersistenceResult<PersistedStepActivation>) results.get(0);
        assertEquals(PersistenceOutcome.APPLIED, activation.outcome());
        for (int index = 1; index < results.size(); index++) {
            assertGuarded((PersistenceResult<?>) results.get(index));
        }
        assertEquals(
                activation.value().orElseThrow().activatedCheckpoint(),
                scenario.persistence().checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value().orElseThrow());
        assertEquals(
                2,
                scenario.persistence().events()
                        .readAfter(PersistenceFixtures.PLAN_ID, 0)
                        .value().orElseThrow().size());
    }

    @Test
    void ordinaryReceiptAppendAndActivationBothCommitWithoutAuthorityCrosstalk()
            throws Exception {
        Scenario scenario = scenario();
        var receipt = PersistenceFixtures.receipt(
                "receipt-race", "receipt-race-tool");
        List<Object> results = raceObjects(List.of(
                () -> scenario.persistence().stepActivations()
                        .activate(scenario.request()),
                () -> scenario.persistence().receipts().append(receipt)));
        @SuppressWarnings("unchecked")
        PersistenceResult<PersistedStepActivation> activation =
                (PersistenceResult<PersistedStepActivation>) results.get(0);
        @SuppressWarnings("unchecked")
        PersistenceResult<io.paperagent.v2.contracts.ExecutionReceipt>
                receiptAppend =
                (PersistenceResult<io.paperagent.v2.contracts.ExecutionReceipt>)
                        results.get(1);
        assertEquals(PersistenceOutcome.APPLIED, activation.outcome());
        assertEquals(PersistenceOutcome.APPLIED, receiptAppend.outcome());
        assertEquals(
                receipt,
                scenario.persistence().receipts()
                        .find(receipt.id()).value().orElseThrow());
        assertEquals(
                activation.value().orElseThrow().activatedCheckpoint(),
                scenario.persistence().checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value().orElseThrow());
        assertTrue(activation.value().orElseThrow()
                .activatedCheckpoint().checkpoint()
                .receiptReferences().isEmpty());
        assertEquals(
                2,
                scenario.persistence().events()
                        .readAfter(PersistenceFixtures.PLAN_ID, 0)
                        .value().orElseThrow().size());
    }

    @Test
    void sameEventIdAcrossPlansCommitsForAtMostOnePlan() throws Exception {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        Plan first = PersistenceFixtures.plan();
        TaskFrameId secondTaskId = new TaskFrameId("task-2");
        PlanId secondPlanId = new PlanId("plan-2");
        TaskFrame secondTask = PersistenceFixtures.taskFrame(
                secondTaskId, "Second objective");
        Plan second = PersistenceFixtures.plan(
                secondPlanId, secondTaskId, "second");
        requireApplied(persistence.planBootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                first,
                PersistenceFixtures.initialCheckpoint(first)));
        requireApplied(persistence.planBootstraps().bootstrap(
                secondTask,
                second,
                PersistenceFixtures.initialCheckpoint(second)));
        LeaseRecord firstLease = requireApplied(persistence.leases().acquire(
                first.id(), "worker-1", "token-1",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        LeaseRecord secondLease = requireApplied(persistence.leases().acquire(
                second.id(), "worker-2", "token-2",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(persistence.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        first, "token-1", firstLease.fencingToken(), "start-1")));
        requireApplied(persistence.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        second, "token-2", secondLease.fencingToken(), "start-2")));
        confirmContext(
                persistence,
                first,
                "token-1",
                firstLease.fencingToken(),
                "shared-first");
        confirmContext(
                persistence,
                second,
                "token-2",
                secondLease.fencingToken(),
                "shared-second");
        StepActivationRequest firstRequest = activationFor(
                first, "token-1", firstLease.fencingToken(),
                "shared-activation-id");
        StepActivationRequest secondRequest = activationFor(
                second, "token-2", secondLease.fencingToken(),
                "shared-activation-id");

        List<PersistenceResult<PersistedStepActivation>> results = race(List.of(
                () -> persistence.stepActivations().activate(firstRequest),
                () -> persistence.stepActivations().activate(secondRequest)));

        assertEquals(
                1,
                results.stream()
                        .filter(result ->
                                result.outcome() == PersistenceOutcome.APPLIED)
                        .count());
        PersistenceResult<PersistedStepActivation> rejected = results.stream()
                .filter(result ->
                        result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                rejected.failure().orElseThrow().code());
        assertEquals(
                "request.activationEvent.id",
                rejected.failure().orElseThrow().path());
    }

    @Test
    void differentPlansAndEventIdsActivateIndependently() throws Exception {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        PlanBootstrapRepository bootstraps =
                new InMemoryPlanBootstrapRepository(state);
        LeaseRepository leases = new InMemoryLeaseRepository(state);
        ExecutionStartRepository starts =
                new InMemoryExecutionStartRepository(state);
        StepActivationRepository activations =
                new InMemoryStepActivationRepository(state);
        PlanExecutionContextRepository contexts =
                new InMemoryPlanExecutionContextRepository(state);

        Plan first = PersistenceFixtures.plan();
        TaskFrameId secondTaskId = new TaskFrameId("task-independent-2");
        PlanId secondPlanId = new PlanId("plan-independent-2");
        TaskFrame secondTask = PersistenceFixtures.taskFrame(
                secondTaskId, "Independent second objective");
        Plan second = PersistenceFixtures.plan(
                secondPlanId, secondTaskId, "independent-2");
        requireApplied(bootstraps.bootstrap(
                PersistenceFixtures.taskFrame(),
                first,
                PersistenceFixtures.initialCheckpoint(first)));
        requireApplied(bootstraps.bootstrap(
                secondTask,
                second,
                PersistenceFixtures.initialCheckpoint(second)));
        LeaseRecord firstLease = requireApplied(leases.acquire(
                first.id(),
                "independent-owner-1",
                "independent-token-1",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        LeaseRecord secondLease = requireApplied(leases.acquire(
                second.id(),
                "independent-owner-2",
                "independent-token-2",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(starts.start(
                PersistenceFixtures.executionStartRequest(
                        first,
                        "independent-token-1",
                        firstLease.fencingToken(),
                        "independent-start-1")));
        requireApplied(starts.start(
                PersistenceFixtures.executionStartRequest(
                        second,
                        "independent-token-2",
                        secondLease.fencingToken(),
                        "independent-start-2")));
        PersistenceFixtures.confirmExecutionContext(
                contexts,
                first,
                "independent-token-1",
                firstLease.fencingToken(),
                PersistenceFixtures.workspaceSpec("independent-first"));
        PersistenceFixtures.confirmExecutionContext(
                contexts,
                second,
                "independent-token-2",
                secondLease.fencingToken(),
                PersistenceFixtures.workspaceSpec("independent-second"));
        StepActivationRequest firstRequest = activationFor(
                first,
                "independent-token-1",
                firstLease.fencingToken(),
                "independent-activation-1");
        StepActivationRequest secondRequest = activationFor(
                second,
                "independent-token-2",
                secondLease.fencingToken(),
                "independent-activation-2");

        List<PersistenceResult<PersistedStepActivation>> results = race(List.of(
                () -> activations.activate(firstRequest),
                () -> activations.activate(secondRequest)));

        assertTrue(results.stream().allMatch(result ->
                result.outcome() == PersistenceOutcome.APPLIED));
        assertEquals(
                firstRequest.activationEvent().id(),
                state.executionMutationHeads.get(first.id()).mutationEventId());
        assertEquals(
                secondRequest.activationEvent().id(),
                state.executionMutationHeads.get(second.id()).mutationEventId());
        assertEquals(3, state.checkpoints.get(first.id()).version());
        assertEquals(3, state.checkpoints.get(second.id()).version());
        assertEquals(2, state.eventStreams.get(first.id()).size());
        assertEquals(2, state.eventStreams.get(second.id()).size());
        assertEquals(
                firstRequest.activationEvent(),
                state.eventStreams.get(first.id()).lastEntry().getValue());
        assertEquals(
                secondRequest.activationEvent(),
                state.eventStreams.get(second.id()).lastEntry().getValue());
        assertNotEquals(
                state.executionMutationHeads.get(first.id()),
                state.executionMutationHeads.get(second.id()));
    }

    @Test
    void staleWorkerCannotUseOrdinaryPortsOrOldFenceAfterTakeover() {
        Scenario scenario = scenario();
        requireApplied(scenario.persistence().leases().release(
                PersistenceFixtures.PLAN_ID, TOKEN));
        LeaseRecord takeover = requireApplied(
                scenario.persistence().leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));

        PersistenceResult<PersistedStepActivation> oldFence =
                scenario.persistence().stepActivations().activate(
                        scenario.request());
        assertEquals(
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                oldFence.failure().orElseThrow().code());
        assertEquals(2, takeover.fencingToken());
        assertGuarded(scenario.persistence().events().append(
                PersistenceFixtures.event("stale-event", 5)));
        assertGuarded(scenario.persistence().plans().appendRevision(
                PersistenceFixtures.PLAN_ID,
                1,
                PersistenceFixtures.revision2(
                        "stale-revision", "stale worker")));
        assertGuarded(scenario.persistence().checkpoints().save(
                2,
                PersistenceFixtures.startedCheckpoint(
                        PersistenceFixtures.plan())));
    }

    private static Scenario scenario() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0);
        InMemoryPersistence persistence =
                PersistenceFixtures.bootstrappedPersistence(clock);
        LeaseRecord lease = requireApplied(persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(persistence.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        lease.fencingToken(),
                        "start-concurrency")));
        confirmContext(
                persistence,
                PersistenceFixtures.plan(),
                TOKEN,
                lease.fencingToken(),
                "concurrency");
        StepActivationRequest request =
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        lease.fencingToken(),
                        "activation-concurrency");
        return new Scenario(persistence, request);
    }

    private static void confirmContext(
            InMemoryPersistence persistence,
            Plan plan,
            String token,
            long fence,
            String suffix) {
        PersistenceFixtures.confirmExecutionContext(
                persistence.planExecutionContexts(),
                plan,
                token,
                fence,
                PersistenceFixtures.workspaceSpec(suffix));
    }

    private static StepActivationRequest activationFor(
            Plan plan,
            String token,
            long fence,
            String eventId) {
        return PersistenceFixtures.stepActivationRequest(
                plan,
                PersistenceFixtures.startedCheckpoint(plan),
                2,
                1,
                token,
                fence,
                PersistenceFixtures.STEP_1,
                eventId,
                3);
    }

    private static StepActivationRequest withEventIdentity(
            StepActivationRequest source,
            String eventId,
            long sequence) {
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId),
                source.activationEvent().taskFrameId(),
                source.activationEvent().planId(),
                sequence,
                source.activationEvent().occurredAt().plusSeconds(1),
                source.activationEvent().type(),
                source.activationEvent().causationId(),
                source.activationEvent().correlationId(),
                source.activationEvent().payload());
        var checkpoint = new io.paperagent.v2.contracts.Checkpoint(
                source.activatedCheckpoint().taskFrameId(),
                source.activatedCheckpoint().planId(),
                source.activatedCheckpoint().revisionId(),
                source.activatedCheckpoint().revisionNumber(),
                sequence,
                source.activatedCheckpoint().planState(),
                source.activatedCheckpoint().stepStates(),
                source.activatedCheckpoint().receiptReferences(),
                source.activatedCheckpoint().createdAt().plusSeconds(1));
        return new StepActivationRequest(
                source.planId(),
                source.leaseToken(),
                source.fencingToken(),
                source.expectedRevisionId(),
                source.expectedRevisionNumber(),
                source.expectedCheckpointVersion(),
                source.expectedEventHeadSequence(),
                source.stepId(),
                event,
                checkpoint);
    }

    private static List<PersistenceResult<PersistedStepActivation>> race(
            List<Callable<PersistenceResult<PersistedStepActivation>>> calls)
            throws Exception {
        List<Object> values = raceObjects(new ArrayList<>(calls));
        List<PersistenceResult<PersistedStepActivation>> results =
                new ArrayList<>();
        for (Object value : values) {
            @SuppressWarnings("unchecked")
            PersistenceResult<PersistedStepActivation> result =
                    (PersistenceResult<PersistedStepActivation>) value;
            results.add(result);
        }
        return results;
    }

    private static List<Object> raceObjects(
            List<? extends Callable<?>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("race did not start");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Object> values = new ArrayList<>();
            for (Future<?> future : futures) {
                values.add(future.get(5, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertGuarded(PersistenceResult<?> result) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                result.failure().orElseThrow().code());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private record Scenario(
            InMemoryPersistence persistence,
            StepActivationRequest request) {
    }
}
