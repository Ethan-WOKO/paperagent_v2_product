package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
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

class PlanReplanRepositoryConcurrencyTest {
    private static final String OWNER = "replan-race-owner";
    private static final String TOKEN = "replan-race-token";

    @Test
    void concurrentExactReplansApplyOnceAndReplayOneDurableRevision()
            throws Exception {
        Scenario scenario = scenario("exact");
        PlanReplanRequest request = request(scenario, "exact", "revision-race-exact");
        List<Callable<PersistenceResult<PersistedPlanReplan>>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(() -> scenario.replans().replan(request));
        }

        List<PersistenceResult<PersistedPlanReplan>> results = race(calls);

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(23, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, results.stream().map(result ->
                result.value().orElseThrow()).distinct().count());
        assertEquals(2, scenario.state().plans.get(scenario.plan().id()).revisions().size());
        assertEquals(1, scenario.state().planReplans.get(scenario.plan().id()).size());
        assertEquals(1, scenario.state().executionMutationLinks.get(scenario.plan().id()).size());
    }

    @Test
    void conflictingSameIdentityCannotForkRevisionCheckpointOrProvenance()
            throws Exception {
        Scenario scenario = scenario("conflict");
        PlanReplanRequest left = request(scenario, "conflict", "revision-race-left");
        PlanReplanRequest right = request(scenario, "conflict", "revision-race-right");

        List<PersistenceResult<PersistedPlanReplan>> results = race(List.of(
                () -> scenario.replans().replan(left),
                () -> scenario.replans().replan(right)));

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        PersistenceResult<PersistedPlanReplan> conflict = results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                conflict.failure().orElseThrow().code());
        assertEquals("request.replanEvent.id",
                conflict.failure().orElseThrow().path());
        assertEquals(2, scenario.state().plans.get(scenario.plan().id()).revisions().size());
        assertEquals(2, scenario.state().eventStreams.get(scenario.plan().id()).size());
        assertEquals(1, scenario.state().planReplans.get(scenario.plan().id()).size());
        assertEquals(1, scenario.state().executionMutationLinks.get(scenario.plan().id()).size());
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
                        plan, TOKEN, lease.fencingToken(), "start-replan-race-" + suffix)));
        return new Scenario(state, plan, new InMemoryPlanReplanRepository(state));
    }

    private static PlanReplanRequest request(
            Scenario scenario,
            String eventSuffix,
            String revisionId) {
        Plan plan = scenario.plan();
        Checkpoint source = scenario.state().checkpoints.get(plan.id()).checkpoint();
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId(revisionId),
                plan.taskFrameId(),
                2,
                Optional.of(plan.latestRevision().id()),
                "race replan",
                source.createdAt().plusSeconds(1),
                plan.latestRevision().steps(),
                plan.latestRevision().completedFacts());
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        revision.steps().forEach(step -> states.put(step.id(), StepExecutionState.NOT_STARTED));
        Checkpoint target = new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                source.createdAt().plusSeconds(1));
        return new PlanReplanRequest(
                plan.id(), TOKEN, 1,
                plan.latestRevision().id(), 1, 2, 1,
                PersistenceFixtures.event("replan-race-" + eventSuffix,
                        plan.taskFrameId(), plan.id(), 2),
                revision, target);
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
            PlanReplanRepository replans) {
    }
}
