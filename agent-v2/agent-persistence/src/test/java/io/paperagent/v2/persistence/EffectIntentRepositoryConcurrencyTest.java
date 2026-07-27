package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectIntentRepositoryConcurrencyTest {
    private static final String OWNER = "effect-race-owner";
    private static final String TOKEN = "effect-race-token";

    @Test
    void concurrentExactRequestsApplyOnceAndReplayTheOneDurableFact()
            throws Exception {
        Scenario scenario = scenario("exact");
        EffectIntentRequest request = request(
                scenario,
                "effect-race-call",
                "workspace.edit",
                "input-exact");
        List<Callable<PersistenceResult<PersistedEffectIntent>>> calls =
                new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(() -> scenario.repository().persist(request));
        }

        List<PersistenceResult<PersistedEffectIntent>> results = race(calls);

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(23, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, results.stream()
                .map(result -> result.value().orElseThrow())
                .distinct()
                .count());
        assertEquals(1, scenario.state().effectIntents.size());
        assertEquals(1, scenario.state().stepActivations.size());
        assertEquals(2, scenario.state().eventStreams
                .get(scenario.plan().id()).size());
        assertTrue(scenario.state().idempotency.isEmpty());
    }

    @Test
    void concurrentConflictsCannotReplaceTheOriginalDurableIntent()
            throws Exception {
        Scenario scenario = scenario("conflict");
        EffectIntentRequest first = request(
                scenario,
                "effect-race-conflict",
                "workspace.edit",
                "input-first");
        EffectIntentRequest changed = request(
                scenario,
                "effect-race-conflict",
                "workspace.read",
                "input-second");

        List<PersistenceResult<PersistedEffectIntent>> results = race(List.of(
                () -> scenario.repository().persist(first),
                () -> scenario.repository().persist(changed)));

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        PersistenceResult<PersistedEffectIntent> conflict = results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                conflict.failure().orElseThrow().code());
        assertEquals("request.intent.kind", conflict.failure().orElseThrow().path());
        PersistedEffectIntent durable = scenario.repository().find(
                first.intent().toolCallId()).value().orElseThrow();
        assertTrue(durable.intent().equals(first.intent())
                || durable.intent().equals(changed.intent()));
        assertEquals(1, scenario.state().effectIntents.size());
        assertEquals(1, scenario.state().stepActivations.size());
        assertTrue(scenario.state().idempotency.isEmpty());
    }

    private static Scenario scenario(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(),
                plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(),
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-race-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("race-" + suffix));
        EventId activation = new EventId("activation-race-" + suffix);
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(), activation.value())));
        return new Scenario(
                state,
                plan,
                lease,
                activation,
                new InMemoryEffectIntentRepository(state));
    }

    private static EffectIntentRequest request(
            Scenario scenario,
            String toolCallId,
            String kind,
            String input) {
        EffectIntent intent = new EffectIntent(
                new ToolCallId(toolCallId),
                scenario.plan().id(),
                PersistenceFixtures.STEP_1,
                kind,
                new ObjectValue(Map.of("input", new TextValue(input))));
        return new EffectIntentRequest(
                intent,
                TOKEN,
                scenario.lease().fencingToken(),
                scenario.activationEventId());
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
            LeaseRecord lease,
            EventId activationEventId,
            EffectIntentRepository repository) {
    }
}
