package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistenceResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SingleTurnStepKernelConcurrencyTest {

    @Test
    void concurrentIndependentRecoveriesKeepTheirOwnAuthorityAndIntent() throws Exception {
        List<RecoveredActiveStep> recoveries = new ArrayList<>();
        Map<PlanId, EffectIntent> intents = new ConcurrentHashMap<>();
        for (int index = 0; index < 16; index++) {
            RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered(
                    "concurrent-" + index);
            recoveries.add(recovered);
            intents.put(recovered.planId(), SingleTurnStepKernelTestFixtures.intent(
                    recovered, "concurrent-" + index));
        }
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.applied(new PersistedEffectIntent(
                        request.intent(),
                        "owner-" + request.leaseToken().substring("token-".length()),
                        request.fencingToken(),
                        request.expectedActivationEventId())));
        SingleTurnStepKernel kernel = new DefaultSingleTurnStepKernel(
                input -> new EffectIntentDecision(intents.get(input.plan().id())),
                repository);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<SingleTurnStepKernelOutcome>> tasks = recoveries.stream()
                    .<Callable<SingleTurnStepKernelOutcome>>map(recovered ->
                            () -> kernel.run(new SingleTurnStepKernelRequest(recovered)))
                    .toList();
            List<Future<SingleTurnStepKernelOutcome>> futures = executor.invokeAll(tasks);

            for (int index = 0; index < futures.size(); index++) {
                SingleTurnIntentPersisted persisted = assertInstanceOf(
                        SingleTurnIntentPersisted.class, futures.get(index).get());
                RecoveredActiveStep recovered = recoveries.get(index);
                assertEquals(recovered.planId(), persisted.planId());
                assertEquals(recovered.recovery().activation().stepId(), persisted.stepId());
                assertEquals(intents.get(recovered.planId()), persisted.persistedIntent().intent());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(16, repository.persistCalls());
        assertEquals(16, repository.requests().size());
        for (var request : repository.requests()) {
            assertEquals(intents.get(request.intent().planId()), request.intent());
        }
    }

    @Test
    void concurrentReplaysLeaveDuplicateResolutionToTheExistingFence() throws Exception {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("replay-race");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "replay-race");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.replayed(
                        SingleTurnStepKernelTestFixtures.persisted(recovered, request.intent())));
        SingleTurnStepKernel kernel = new DefaultSingleTurnStepKernel(
                input -> new EffectIntentDecision(intent), repository);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Callable<SingleTurnStepKernelOutcome>> tasks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                tasks.add(() -> kernel.run(new SingleTurnStepKernelRequest(recovered)));
            }
            for (Future<SingleTurnStepKernelOutcome> future : executor.invokeAll(tasks)) {
                SingleTurnIntentPersisted persisted = assertInstanceOf(
                        SingleTurnIntentPersisted.class, future.get());
                assertEquals(intent, persisted.persistedIntent().intent());
                assertEquals(recovered.planId(), persisted.planId());
                assertEquals(recovered.recovery().activation().stepId(), persisted.stepId());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(20, repository.persistCalls());
        assertEquals(20, repository.requests().size());
        for (var request : repository.requests()) {
            assertEquals(intent, request.intent());
            assertEquals(recovered.lease().fencingToken(), request.fencingToken());
            assertEquals(recovered.recovery().activation().activationEvent().id(),
                    request.expectedActivationEventId());
        }
    }
}
