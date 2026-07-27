package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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

class EffectOutcomeRepositoryConcurrencyTest {
    private static final String OWNER = "outcome-race-owner";
    private static final String TOKEN = "outcome-race-token";

    @Test
    void concurrentExactProgressAndResultRequestsApplyOnceThenReplay() throws Exception {
        Scenario scenario = scenario("exact");
        EffectProgressRequest progress = progressRequest(
                scenario.toolCallId(), "exact", 1);
        List<Callable<PersistenceResult<PersistedEffectProgress>>> progressCalls =
                new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            progressCalls.add(() -> scenario.outcomes().appendProgress(progress));
        }

        List<PersistenceResult<PersistedEffectProgress>> progressResults = race(progressCalls);

        assertEquals(1, progressResults.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(23, progressResults.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, progressResults.stream()
                .map(result -> result.value().orElseThrow()).distinct().count());

        EffectResultRequest result = new EffectResultRequest(
                receipt("exact", scenario.toolCallId().value()),
                TOKEN,
                scenario.lease().fencingToken());
        List<Callable<PersistenceResult<PersistedEffectResult>>> resultCalls =
                new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            resultCalls.add(() -> scenario.outcomes().recordResult(result));
        }

        List<PersistenceResult<PersistedEffectResult>> resultResults = race(resultCalls);

        assertEquals(1, resultResults.stream().filter(value ->
                value.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(23, resultResults.stream().filter(value ->
                value.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, resultResults.stream()
                .map(value -> value.value().orElseThrow()).distinct().count());
        assertEquals(1, scenario.state().effectProgresses
                .get(scenario.toolCallId()).size());
        assertEquals(1, scenario.state().effectResults.size());
        assertEquals(1, scenario.state().receipts.size());
    }

    @Test
    void concurrentConflictingResultCannotReplaceTheDurableReceipt() throws Exception {
        Scenario scenario = scenario("conflict");
        EffectResultRequest left = new EffectResultRequest(
                receipt("left", scenario.toolCallId().value()),
                TOKEN,
                scenario.lease().fencingToken());
        EffectResultRequest right = new EffectResultRequest(
                receipt("right", scenario.toolCallId().value()),
                TOKEN,
                scenario.lease().fencingToken());

        List<PersistenceResult<PersistedEffectResult>> results = race(List.of(
                () -> scenario.outcomes().recordResult(left),
                () -> scenario.outcomes().recordResult(right)));

        assertEquals(1, results.stream().filter(value ->
                value.outcome() == PersistenceOutcome.APPLIED).count());
        PersistenceResult<PersistedEffectResult> conflict = results.stream()
                .filter(value -> value.outcome() == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                conflict.failure().orElseThrow().code());
        assertEquals("request.receipt.id", conflict.failure().orElseThrow().path());
        ExecutionReceipt durable = scenario.outcomes().findResult(scenario.toolCallId())
                .value().orElseThrow().receipt();
        assertTrue(durable.equals(left.receipt()) || durable.equals(right.receipt()));
        assertEquals(durable, scenario.receipts().find(durable.id()).value().orElseThrow());
        assertEquals(1, scenario.state().effectResults.size());
        assertEquals(1, scenario.state().receipts.size());
    }

    private static Scenario scenario(String suffix) {
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
                        plan, TOKEN, lease.fencingToken(), "start-race-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan, TOKEN, lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("race-outcome-" + suffix));
        EventId activation = new EventId("activation-race-" + suffix);
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(), activation.value())));
        ToolCallId toolCallId = new ToolCallId("effect-race-" + suffix);
        EffectIntentRepository intents = new InMemoryEffectIntentRepository(state);
        requireApplied(intents.persist(new EffectIntentRequest(
                new EffectIntent(
                        toolCallId,
                        plan.id(),
                        PersistenceFixtures.STEP_1,
                        "workspace.edit",
                        new ObjectValue(Map.of("input", new TextValue("race-" + suffix)))),
                TOKEN,
                lease.fencingToken(),
                activation)));
        return new Scenario(
                state,
                lease,
                toolCallId,
                new InMemoryEffectOutcomeRepository(state),
                new InMemoryReceiptRepository(state));
    }

    private static EffectProgressRequest progressRequest(
            ToolCallId toolCallId,
            String suffix,
            long sequence) {
        return new EffectProgressRequest(new EffectProgress(
                new EffectProgressId("progress-race-" + suffix),
                toolCallId,
                sequence,
                PersistenceFixtures.T0.plusSeconds(sequence),
                new ObjectValue(Map.of("detail", new TextValue("race-" + suffix)))),
                TOKEN,
                1);
    }

    private static ExecutionReceipt receipt(String suffix, String toolCallId) {
        return new ExecutionReceipt(
                new ReceiptId("receipt-race-" + suffix),
                new ToolCallId(toolCallId),
                ReceiptStatus.SUCCESS,
                PersistenceFixtures.T0,
                PersistenceFixtures.T0.plusSeconds(1),
                Optional.of(0),
                Optional.empty(),
                OutputCapture.empty(),
                OutputCapture.empty(),
                List.of(),
                Optional.empty(),
                List.of());
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
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) {
                values.add(future.get(10, TimeUnit.SECONDS));
            }
            return values;
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
            LeaseRecord lease,
            ToolCallId toolCallId,
            EffectOutcomeRepository outcomes,
            ReceiptRepository receipts) {
    }
}
