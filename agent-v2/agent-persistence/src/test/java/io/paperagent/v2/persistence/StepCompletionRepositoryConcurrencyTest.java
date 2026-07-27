package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepCompletionRepositoryConcurrencyTest {
    private static final String OWNER = "completion-race-owner";
    private static final String TOKEN = "completion-race-token";

    @Test
    void concurrentExactCompletionRequestsApplyOnceAndReplayTheDurableFact()
            throws Exception {
        Scenario scenario = scenario("exact");
        StepCompletionRequest request = request(scenario, "exact", "outcome-exact");
        List<Callable<PersistenceResult<PersistedStepCompletion>>> calls =
                new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(() -> scenario.completions().complete(request));
        }

        List<PersistenceResult<PersistedStepCompletion>> results = race(calls);

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(23, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, results.stream().map(result ->
                result.value().orElseThrow()).distinct().count());
        assertEquals(2, scenario.state().plans.get(scenario.plan().id()).revisions().size());
        assertEquals(1, scenario.state().stepCompletions.get(scenario.plan().id()).size());
        assertEquals(2, scenario.state().executionMutationLinks.get(scenario.plan().id()).size());
    }

    @Test
    void conflictingSameCompletionIdentityCannotForkTheRevisionOrProvenance()
            throws Exception {
        Scenario scenario = scenario("conflict");
        StepCompletionRequest left = request(scenario, "conflict", "outcome-left");
        StepCompletionRequest right = request(scenario, "conflict", "outcome-right");

        List<PersistenceResult<PersistedStepCompletion>> results = race(List.of(
                () -> scenario.completions().complete(left),
                () -> scenario.completions().complete(right)));

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        PersistenceResult<PersistedStepCompletion> conflict = results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                conflict.failure().orElseThrow().code());
        assertEquals("request.completionEvent.id",
                conflict.failure().orElseThrow().path());
        assertEquals(2, scenario.state().plans.get(scenario.plan().id()).revisions().size());
        assertEquals(3, scenario.state().eventStreams.get(scenario.plan().id()).size());
        assertEquals(1, scenario.state().stepCompletions.get(scenario.plan().id()).size());
        assertEquals(2, scenario.state().executionMutationLinks.get(scenario.plan().id()).size());
    }

    private static Scenario scenario(String suffix) {
        InMemoryState state = new InMemoryState(
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0));
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
                        plan, TOKEN, lease.fencingToken(), "start-race-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state), plan, TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("race-completion-" + suffix));
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(),
                        "activation-race-" + suffix)));
        return new Scenario(state, plan, new InMemoryStepCompletionRepository(state));
    }

    private static StepCompletionRequest request(
            Scenario scenario,
            String eventSuffix,
            String outcomeHash) {
        Plan plan = scenario.plan();
        Checkpoint source = scenario.state().checkpoints.get(plan.id()).checkpoint();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                outcomeHash,
                source.createdAt().plusSeconds(1),
                List.of());
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>();
        facts.put(PersistenceFixtures.STEP_1, fact);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-race-" + eventSuffix),
                plan.taskFrameId(),
                2,
                Optional.of(plan.latestRevision().id()),
                "race completion",
                source.createdAt().plusSeconds(1),
                plan.latestRevision().steps(),
                facts);
        Checkpoint target = new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                revision.id(),
                revision.number(),
                4,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.SUCCEEDED,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                List.of(),
                source.createdAt().plusSeconds(1));
        return new StepCompletionRequest(
                plan.id(),
                TOKEN,
                1,
                plan.latestRevision().id(),
                1,
                3,
                3,
                PersistenceFixtures.STEP_1,
                fact,
                PersistenceFixtures.event(
                        "completion-race-" + eventSuffix,
                        plan.taskFrameId(), plan.id(), 4),
                revision,
                target);
    }

    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("race start timed out");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private record Scenario(
            InMemoryState state,
            Plan plan,
            StepCompletionRepository completions) {
    }
}
