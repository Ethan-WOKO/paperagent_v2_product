package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnIntentPersisted;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class BoundedStepAgentLoopConcurrencyTest {

    @Test
    void independentConcurrentRunsKeepTheirAuthorityIntentOrderAndLocalState() throws Exception {
        List<RecoveredActiveStep> recoveries = new ArrayList<>();
        Map<PlanId, List<io.paperagent.v2.persistence.PersistedEffectIntent>> intents =
                new ConcurrentHashMap<>();
        Map<PlanId, AtomicInteger> callsByPlan = new ConcurrentHashMap<>();
        for (int index = 0; index < 16; index++) {
            RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered(
                    "concurrent-" + index);
            recoveries.add(recovered);
            intents.put(recovered.planId(), List.of(
                    BoundedStepAgentLoopTestFixtures.persisted(recovered, "concurrent-" + index + "-1"),
                    BoundedStepAgentLoopTestFixtures.persisted(recovered, "concurrent-" + index + "-2")));
            callsByPlan.put(recovered.planId(), new AtomicInteger());
        }
        var kernel = new BoundedStepAgentLoopTestFixtures.RecordingKernel(request -> {
            RecoveredActiveStep recovered = request.recoveredStep();
            int turn = callsByPlan.get(recovered.planId()).getAndIncrement();
            if (turn < 2) {
                return new SingleTurnIntentPersisted(intents.get(recovered.planId()).get(turn));
            }
            return new SingleTurnNoEffect(
                    recovered.planId(), recovered.recovery().activation().stepId());
        });
        BoundedStepAgentLoop loop = new DefaultBoundedStepAgentLoop(kernel);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<BoundedStepAgentLoopOutcome>> tasks = recoveries.stream()
                    .<Callable<BoundedStepAgentLoopOutcome>>map(recovered ->
                            () -> loop.run(new BoundedStepAgentLoopRequest(recovered, 8)))
                    .toList();
            List<Future<BoundedStepAgentLoopOutcome>> futures = executor.invokeAll(tasks);

            for (int index = 0; index < futures.size(); index++) {
                BoundedStepAgentLoopNoEffect outcome = assertInstanceOf(
                        BoundedStepAgentLoopNoEffect.class, futures.get(index).get());
                RecoveredActiveStep recovered = recoveries.get(index);
                assertEquals(recovered.planId(), outcome.planId());
                assertEquals(recovered.recovery().activation().stepId(), outcome.stepId());
                assertEquals(3, outcome.turnsExecuted());
                assertEquals(intents.get(recovered.planId()), outcome.persistedIntents());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(48, kernel.calls());
        for (var request : kernel.requests()) {
            RecoveredActiveStep expected = recoveries.stream()
                    .filter(candidate -> candidate.planId().equals(request.recoveredStep().planId()))
                    .findFirst()
                    .orElseThrow();
            assertSame(expected, request.recoveredStep());
        }
    }

    @Test
    void concurrentReplayRunsLeaveDuplicateIntentResolutionToTheSingleTurnKernel() throws Exception {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("replay");
        var replayedIntent = BoundedStepAgentLoopTestFixtures.persisted(recovered, "replay");
        var kernel = new BoundedStepAgentLoopTestFixtures.RecordingKernel(
                request -> new SingleTurnIntentPersisted(replayedIntent));
        BoundedStepAgentLoop loop = new DefaultBoundedStepAgentLoop(kernel);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Callable<BoundedStepAgentLoopOutcome>> tasks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                tasks.add(() -> loop.run(new BoundedStepAgentLoopRequest(recovered, 1)));
            }
            for (Future<BoundedStepAgentLoopOutcome> future : executor.invokeAll(tasks)) {
                BoundedStepAgentLoopTurnLimitReached outcome = assertInstanceOf(
                        BoundedStepAgentLoopTurnLimitReached.class, future.get());
                assertEquals(1, outcome.turnsExecuted());
                assertEquals(List.of(replayedIntent), outcome.persistedIntents());
                assertEquals(recovered.planId(), outcome.planId());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(20, kernel.calls());
        for (var request : kernel.requests()) {
            assertSame(recovered, request.recoveredStep());
        }
    }
}
