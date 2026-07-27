package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnIntentPersisted;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnPersistenceRejected;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelOutcome;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class BoundedStepAgentLoopTest {

    @Test
    void immediateNoEffectStopsAfterOneTurnWithNoDurableIntent() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("immediate-no-effect");
        AtomicInteger calls = new AtomicInteger();
        List<SingleTurnStepKernelRequest> requests = new ArrayList<>();
        var kernel = BoundedStepAgentLoopTestFixtures.scripted(
                List.of(new SingleTurnNoEffect(
                        recovered.planId(), recovered.recovery().activation().stepId())),
                calls,
                requests);

        BoundedStepAgentLoopNoEffect outcome = assertInstanceOf(
                BoundedStepAgentLoopNoEffect.class,
                new DefaultBoundedStepAgentLoop(kernel).run(
                        new BoundedStepAgentLoopRequest(recovered, 8)));

        assertEquals(1, outcome.turnsExecuted());
        assertEquals(List.of(), outcome.persistedIntents());
        assertEquals(1, calls.get());
        assertEquals(1, requests.size());
    }

    @Test
    void durableTurnsUseTheOriginalAuthorityAndReachTheTypedLimit() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("limit");
        var first = BoundedStepAgentLoopTestFixtures.persisted(recovered, "limit-1");
        var second = BoundedStepAgentLoopTestFixtures.persisted(recovered, "limit-2");
        var third = BoundedStepAgentLoopTestFixtures.persisted(recovered, "limit-3");
        AtomicInteger calls = new AtomicInteger();
        List<SingleTurnStepKernelRequest> requests = new ArrayList<>();
        var kernel = BoundedStepAgentLoopTestFixtures.scripted(
                List.of(
                        new SingleTurnIntentPersisted(first),
                        new SingleTurnIntentPersisted(second),
                        new SingleTurnIntentPersisted(third)),
                calls,
                requests);

        BoundedStepAgentLoopTurnLimitReached outcome = assertInstanceOf(
                BoundedStepAgentLoopTurnLimitReached.class,
                new DefaultBoundedStepAgentLoop(kernel).run(
                        new BoundedStepAgentLoopRequest(recovered, 3)));

        assertEquals(recovered.planId(), outcome.planId());
        assertEquals(recovered.recovery().activation().stepId(), outcome.stepId());
        assertEquals(3, outcome.turnsExecuted());
        assertEquals(List.of(first, second, third), outcome.persistedIntents());
        assertEquals(3, calls.get());
        for (SingleTurnStepKernelRequest request : requests) {
            assertSame(recovered, request.recoveredStep());
        }
    }

    @Test
    void noEffectStopsWithoutCallingTheNextTurn() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("no-effect");
        var first = BoundedStepAgentLoopTestFixtures.persisted(recovered, "no-effect-1");
        var second = BoundedStepAgentLoopTestFixtures.persisted(recovered, "no-effect-2");
        AtomicInteger calls = new AtomicInteger();
        List<SingleTurnStepKernelRequest> requests = new ArrayList<>();
        var kernel = BoundedStepAgentLoopTestFixtures.scripted(
                List.of(
                        new SingleTurnIntentPersisted(first),
                        new SingleTurnIntentPersisted(second),
                        new SingleTurnNoEffect(recovered.planId(),
                                recovered.recovery().activation().stepId())),
                calls,
                requests);

        BoundedStepAgentLoopNoEffect outcome = assertInstanceOf(
                BoundedStepAgentLoopNoEffect.class,
                new DefaultBoundedStepAgentLoop(kernel).run(
                        new BoundedStepAgentLoopRequest(recovered, 8)));

        assertEquals(3, outcome.turnsExecuted());
        assertEquals(List.of(first, second), outcome.persistedIntents());
        assertEquals(3, calls.get());
        assertEquals(3, requests.size());
    }

    @Test
    void persistenceRejectionStopsWithTheKernelFailureAndNoFollowUpTurn() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("rejected");
        var first = BoundedStepAgentLoopTestFixtures.persisted(recovered, "rejected-1");
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID, "fencingToken");
        AtomicInteger calls = new AtomicInteger();
        List<SingleTurnStepKernelRequest> requests = new ArrayList<>();
        var kernel = BoundedStepAgentLoopTestFixtures.scripted(
                List.of(
                        new SingleTurnIntentPersisted(first),
                        new SingleTurnPersistenceRejected(
                                recovered.planId(),
                                recovered.recovery().activation().stepId(),
                                failure)),
                calls,
                requests);

        BoundedStepAgentLoopPersistenceRejected outcome = assertInstanceOf(
                BoundedStepAgentLoopPersistenceRejected.class,
                new DefaultBoundedStepAgentLoop(kernel).run(
                        new BoundedStepAgentLoopRequest(recovered, 8)));

        assertEquals(2, outcome.turnsExecuted());
        assertEquals(List.of(first), outcome.persistedIntents());
        assertSame(failure, outcome.failure());
        assertEquals(2, calls.get());
        assertEquals(2, requests.size());
    }

    @Test
    void maximumAllowedBoundDelegatesExactlySixteenDurableTurns() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("max-bound");
        AtomicInteger calls = new AtomicInteger();
        List<SingleTurnStepKernelRequest> requests = new ArrayList<>();
        List<SingleTurnStepKernelOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            outcomes.add(new SingleTurnIntentPersisted(
                    BoundedStepAgentLoopTestFixtures.persisted(recovered, "max-" + index)));
        }

        BoundedStepAgentLoopTurnLimitReached outcome = assertInstanceOf(
                BoundedStepAgentLoopTurnLimitReached.class,
                new DefaultBoundedStepAgentLoop(BoundedStepAgentLoopTestFixtures.scripted(
                        outcomes, calls, requests)).run(
                                new BoundedStepAgentLoopRequest(recovered, 16)));

        assertEquals(16, outcome.turnsExecuted());
        assertEquals(16, outcome.persistedIntents().size());
        assertEquals(16, calls.get());
        assertEquals(16, requests.size());
    }
}
