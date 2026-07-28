package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.persistence.ActiveStepReplanRepository;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopNoEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BoundedStepReplanCompositionConcurrencyTest {

    @Test
    void concurrentRequestsDoNotCrossAuthorityOrRuntimeState() throws Exception {
        List<BoundedStepReplanCompositionTestFixtures.Scenario> scenarios =
                java.util.stream.IntStream.range(0, 24)
                        .mapToObj(index -> BoundedStepReplanCompositionTestFixtures
                                .scenario("concurrent-" + index))
                        .toList();
        var byPlanId = new ConcurrentHashMap<io.paperagent.v2.contracts.PlanId,
                BoundedStepReplanCompositionTestFixtures.Scenario>();
        for (var scenario : scenarios) {
            byPlanId.put(scenario.recovered().planId(), scenario);
        }
        var calls = new AtomicInteger();
        ActiveStepReplanRepository repository = request -> {
            calls.incrementAndGet();
            var scenario = byPlanId.get(request.planId());
            if (scenario == null) {
                throw new AssertionError("unexpected plan");
            }
            return PersistenceResult.applied(
                    BoundedStepReplanCompositionTestFixtures.persisted(
                            scenario.recovered(), request));
        };
        var composer = new DefaultBoundedStepReplanComposer(repository);
        List<Callable<BoundedStepReplanCompositionOutcome>> callsToRun =
                new ArrayList<>();
        for (int index = 0; index < scenarios.size(); index++) {
            var scenario = scenarios.get(index);
            if (index % 2 == 0) {
                callsToRun.add(() -> composer.compose(
                        scenario.recovered(),
                        scenario.turnLimitReached(),
                        scenario.request()));
            } else {
                var noEffect = new BoundedStepAgentLoopNoEffect(
                        scenario.recovered().planId(),
                        scenario.recovered().recovery().activation().stepId(),
                        1,
                        List.of());
                callsToRun.add(() -> composer.composeNoEffect(
                        scenario.recovered(), noEffect, scenario.request()));
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var outcomes = executor.invokeAll(callsToRun).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertEquals(scenarios.size(), calls.get());
            for (int index = 0; index < scenarios.size(); index++) {
                var applied = assertInstanceOf(
                        BoundedStepReplanApplied.class, outcomes.get(index));
                assertEquals(
                        scenarios.get(index).recovered().planId(),
                        applied.planId());
                assertEquals(
                        scenarios.get(index).request(),
                        requestFrom(applied.persistedReplan()));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static ActiveStepReplanRequest requestFrom(
            PersistedActiveStepReplan persisted) {
        var superseded = persisted.supersededCheckpoint().checkpoint();
        var replanned = persisted.replannedCheckpoint().checkpoint();
        return new ActiveStepReplanRequest(
                persisted.planId(),
                "token-" + persisted.planId().value().substring("plan-".length()),
                persisted.fencingToken(),
                superseded.revisionId(),
                superseded.revisionNumber(),
                persisted.supersededCheckpoint().version() - 1,
                superseded.lastEventSequence() - 1,
                persisted.supersededStepId(),
                persisted.supersessionEvent(),
                superseded,
                persisted.replanEvent(),
                persisted.replannedRevision(),
                replanned);
    }
}
