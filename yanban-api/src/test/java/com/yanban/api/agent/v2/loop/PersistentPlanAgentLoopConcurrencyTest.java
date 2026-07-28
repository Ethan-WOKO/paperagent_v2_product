package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopTestSupport.TURN_ID;
import static com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopTestSupport.USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersistentPlanAgentLoopConcurrencyTest {
    @Test
    void eightConcurrentTerminalRetriesConvergeWithoutDownstreamWork()
            throws Exception {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        PersistedStepRecoverySucceeded succeeded =
                mock(PersistedStepRecoverySucceeded.class);
        when(succeeded.planId()).thenReturn(fixture.planId());
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(succeeded));
        var command = PersistentPlanAgentLoopTestSupport.command(4);
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            var futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(ignored -> pool.submit(() -> {
                        start.await();
                        return fixture.composer().execute(
                                USER_ID, TURN_ID, command);
                    }))
                    .toList();
            start.countDown();
            List<PersistentPlanAgentLoopOutcome> outcomes =
                    futures.stream().map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }).toList();
            assertTrue(outcomes.stream().allMatch(outcome ->
                    outcome.state()
                            == PersistentPlanAgentLoopState
                                    .PLAN_SUCCEEDED
                            && outcome.cyclesAttempted() == 0));
        } finally {
            pool.shutdownNow();
        }

        verify(fixture.inspections(), times(callers))
                .inspect(fixture.planId());
        verifyNoInteractions(
                fixture.recoverer(), fixture.activation(),
                fixture.kernel(), fixture.effects(),
                fixture.progression());
    }

    @Test
    void eightCallersShareNoMutableCommandState() throws Exception {
        var command = PersistentPlanAgentLoopTestSupport.command(4);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> pool.submit(command::maxCycles))
                    .toList();
            for (var future : futures) {
                assertEquals(4, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
