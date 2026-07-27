package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedStepAgentLoopBoundaryTest {

    @Test
    void publicComponentsRejectInvalidBoundsReferencesAndOutcomeCombinations() {
        BoundedStepAgentLoopValidationException missingRecovery = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopRequest(null, 1));
        assertEquals(BoundedStepAgentLoopValidationCode.REQUIRED_VALUE_MISSING,
                missingRecovery.code());
        assertEquals("boundedStepAgentLoopRequest.recoveredStep", missingRecovery.path());

        BoundedStepAgentLoopValidationException lowBound = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopRequest(
                        BoundedStepAgentLoopTestFixtures.recovered("low"), 0));
        assertEquals(BoundedStepAgentLoopValidationCode.INVALID_MAX_TURNS, lowBound.code());

        BoundedStepAgentLoopValidationException highBound = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopRequest(
                        BoundedStepAgentLoopTestFixtures.recovered("high"), 17));
        assertEquals(BoundedStepAgentLoopValidationCode.INVALID_MAX_TURNS, highBound.code());

        BoundedStepAgentLoopValidationException missingKernel = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new DefaultBoundedStepAgentLoop(null));
        assertEquals("boundedStepAgentLoop.singleTurnStepKernel", missingKernel.path());

        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("invalid");
        BoundedStepAgentLoopValidationException invalidNoEffect = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopNoEffect(
                        recovered.planId(),
                        recovered.recovery().activation().stepId(),
                        2,
                        List.of()));
        assertEquals(BoundedStepAgentLoopValidationCode.INVALID_DURABLE_INTENT_COUNT,
                invalidNoEffect.code());

        BoundedStepAgentLoopValidationException invalidLimit = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopTurnLimitReached(
                        recovered.planId(),
                        recovered.recovery().activation().stepId(),
                        1,
                        List.of()));
        assertEquals(BoundedStepAgentLoopValidationCode.INVALID_DURABLE_INTENT_COUNT,
                invalidLimit.code());

        BoundedStepAgentLoopValidationException missingFailure = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopPersistenceRejected(
                        recovered.planId(),
                        recovered.recovery().activation().stepId(),
                        1,
                        List.of(),
                        null));
        assertEquals("boundedStepAgentLoopPersistenceRejected.failure", missingFailure.path());
    }

    @Test
    void durableIntentListsAreCopiedImmutableAndOpaque() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("opaque");
        PersistedEffectIntent intent = BoundedStepAgentLoopTestFixtures.persisted(recovered, "opaque");
        List<PersistedEffectIntent> mutable = new ArrayList<>(List.of(intent));
        BoundedStepAgentLoopTurnLimitReached outcome = new BoundedStepAgentLoopTurnLimitReached(
                recovered.planId(),
                recovered.recovery().activation().stepId(),
                1,
                mutable);
        mutable.clear();

        assertEquals(List.of(intent), outcome.persistedIntents());
        assertThrows(UnsupportedOperationException.class,
                () -> outcome.persistedIntents().add(intent));
        assertFalse(new BoundedStepAgentLoopRequest(recovered, 1).toString()
                .contains(recovered.lease().leaseToken()));
        assertFalse(outcome.toString().contains(intent.intent().kind()));
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_HELD, "failure-secret-opaque");
        assertFalse(new BoundedStepAgentLoopPersistenceRejected(
                recovered.planId(),
                recovered.recovery().activation().stepId(),
                1,
                List.of(),
                failure).toString().contains("failure-secret-opaque"));

        List<PersistedEffectIntent> nullable = new ArrayList<>();
        nullable.add(null);
        BoundedStepAgentLoopValidationException nullIntent = assertThrows(
                BoundedStepAgentLoopValidationException.class,
                () -> new BoundedStepAgentLoopTurnLimitReached(
                        recovered.planId(),
                        recovered.recovery().activation().stepId(),
                        1,
                        nullable));
        assertEquals(BoundedStepAgentLoopValidationCode.NULL_DURABLE_INTENT, nullIntent.code());
    }

    @Test
    void nullThrowingAndAuthorityMismatchedKernelOutcomesFailClosedWithoutRetry() {
        RecoveredActiveStep recovered = BoundedStepAgentLoopTestFixtures.recovered("kernel-failure");
        AtomicInteger nullCalls = new AtomicInteger();
        BoundedStepAgentLoopProtocolException nullOutcome = assertThrows(
                BoundedStepAgentLoopProtocolException.class,
                () -> new DefaultBoundedStepAgentLoop(request -> {
                    nullCalls.incrementAndGet();
                    return null;
                }).run(new BoundedStepAgentLoopRequest(recovered, 4)));
        assertEquals(BoundedStepAgentLoopProtocolCode.NULL_COLLABORATOR_RESULT,
                nullOutcome.code());
        assertEquals(1, nullCalls.get());

        AtomicInteger throwingCalls = new AtomicInteger();
        BoundedStepAgentLoopProtocolException throwingOutcome = assertThrows(
                BoundedStepAgentLoopProtocolException.class,
                () -> new DefaultBoundedStepAgentLoop(request -> {
                    throwingCalls.incrementAndGet();
                    throw new IllegalStateException("kernel-secret-opaque");
                }).run(new BoundedStepAgentLoopRequest(recovered, 4)));
        assertEquals(BoundedStepAgentLoopProtocolCode.COLLABORATOR_EXCEPTION,
                throwingOutcome.code());
        assertEquals(1, throwingCalls.get());
        assertFalse(throwingOutcome.toString().contains("kernel-secret-opaque"));
        assertFalse(throwingOutcome.getCause().toString().contains("kernel-secret-opaque"));
        assertEquals(null, throwingOutcome.getCause().getCause());

        AtomicInteger mismatchedCalls = new AtomicInteger();
        BoundedStepAgentLoopProtocolException mismatchedOutcome = assertThrows(
                BoundedStepAgentLoopProtocolException.class,
                () -> new DefaultBoundedStepAgentLoop(request -> {
                    mismatchedCalls.incrementAndGet();
                    return new SingleTurnNoEffect(
                            new PlanId("other-plan"), new PlanStepId("other-step"));
                }).run(new BoundedStepAgentLoopRequest(recovered, 4)));
        assertEquals(BoundedStepAgentLoopProtocolCode.INCONSISTENT_OUTCOME_AUTHORITY,
                mismatchedOutcome.code());
        assertEquals(1, mismatchedCalls.get());
    }
}
