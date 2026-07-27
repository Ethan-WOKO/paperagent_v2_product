package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleTurnStepKernelTest {

    @Test
    void noEffectInvokesOneTurnAndWritesNoIntent() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("no-effect");
        AtomicInteger turnCalls = new AtomicInteger();
        AtomicReference<StepTurnInput> input = new AtomicReference<>();
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("no-effect turn must not persist");
                });

        SingleTurnStepKernelOutcome outcome = new DefaultSingleTurnStepKernel(
                supplied -> {
                    turnCalls.incrementAndGet();
                    input.set(supplied);
                    return new NoEffectDecision();
                }, repository).run(new SingleTurnStepKernelRequest(recovered));

        SingleTurnNoEffect noEffect = assertInstanceOf(SingleTurnNoEffect.class, outcome);
        assertEquals(recovered.planId(), noEffect.planId());
        assertEquals(recovered.recovery().activation().stepId(), noEffect.stepId());
        assertEquals(1, turnCalls.get());
        assertSame(recovered.recovery().taskFrame(), input.get().taskFrame());
        assertSame(recovered.recovery().plan(), input.get().plan());
        assertSame(recovered.recovery().checkpoint(), input.get().checkpoint());
        assertEquals(recovered.recovery().activation().stepId(), input.get().activeStep().id());
        assertEquals(0, repository.persistCalls());
    }

    @Test
    void appliedIntentUsesRecoveredFenceAndActivationExactlyOnce() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("applied");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "applied");
        AtomicInteger turnCalls = new AtomicInteger();
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.applied(
                        SingleTurnStepKernelTestFixtures.persisted(recovered, request.intent())));

        SingleTurnIntentPersisted persisted = assertInstanceOf(
                SingleTurnIntentPersisted.class,
                new DefaultSingleTurnStepKernel(input -> {
                    turnCalls.incrementAndGet();
                    return new EffectIntentDecision(intent);
                }, repository).run(new SingleTurnStepKernelRequest(recovered)));

        assertSame(intent, persisted.persistedIntent().intent());
        assertEquals(1, turnCalls.get());
        assertEquals(1, repository.persistCalls());
        var request = repository.requests().get(0);
        assertSame(intent, request.intent());
        assertEquals(recovered.lease().leaseToken(), request.leaseToken());
        assertEquals(recovered.lease().fencingToken(), request.fencingToken());
        assertEquals(recovered.recovery().activation().activationEvent().id(),
                request.expectedActivationEventId());
    }

    @Test
    void replayedExactIntentReturnsDurableOutcome() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("replayed");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "replayed");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.replayed(
                        SingleTurnStepKernelTestFixtures.persisted(recovered, request.intent())));

        SingleTurnIntentPersisted persisted = assertInstanceOf(
                SingleTurnIntentPersisted.class,
                new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertSame(intent, persisted.persistedIntent().intent());
        assertEquals(1, repository.persistCalls());
    }

    @Test
    void validPersistenceRejectionIsTypedAfterExactlyOneWrite() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("rejected");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "rejected");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID, "fencingToken"));

        SingleTurnPersistenceRejected rejected = assertInstanceOf(
                SingleTurnPersistenceRejected.class,
                new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(recovered.planId(), rejected.planId());
        assertEquals(recovered.recovery().activation().stepId(), rejected.stepId());
        assertEquals(PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID, rejected.failure().code());
        assertEquals(1, repository.persistCalls());
    }

    @Test
    void decisionWithDifferentPlanFailsBeforePersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("wrong-plan");
        EffectIntent original = SingleTurnStepKernelTestFixtures.intent(recovered, "wrong-plan");
        EffectIntent mismatched = new EffectIntent(
                original.toolCallId(),
                new PlanId("plan-other"),
                original.stepId(),
                original.kind(),
                original.arguments());
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("invalid authority must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(mismatched), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelStage.TURN_DECISION, exception.stage());
        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_DECISION_AUTHORITY,
                exception.code());
        assertEquals(0, repository.persistCalls());
    }

    @Test
    void decisionWithDifferentStepFailsBeforePersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("wrong-step");
        EffectIntent original = SingleTurnStepKernelTestFixtures.intent(recovered, "wrong-step");
        EffectIntent mismatched = new EffectIntent(
                original.toolCallId(),
                original.planId(),
                new PlanStepId("step-other"),
                original.kind(),
                original.arguments());
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("invalid authority must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(mismatched), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_DECISION_AUTHORITY,
                exception.code());
        assertEquals(0, repository.persistCalls());
    }

    @Test
    void nullPortDecisionFailsClosedWithoutPersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("null-turn");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("null turn must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(input -> null, repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.NULL_COLLABORATOR_RESULT, exception.code());
        assertEquals(SingleTurnStepKernelStage.TURN_DECISION, exception.stage());
        assertEquals(0, repository.persistCalls());
    }

    @Test
    void throwingPortIsSanitizedAndNeverRetried() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("throwing-port");
        AtomicInteger turns = new AtomicInteger();
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("throwing turn must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(input -> {
                    turns.incrementAndGet();
                    throw new IllegalStateException("turn-secret-opaque");
                }, repository).run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.COLLABORATOR_EXCEPTION, exception.code());
        assertEquals(1, turns.get());
        assertEquals(0, repository.persistCalls());
        assertFalse(exception.toString().contains("turn-secret-opaque"));
        assertFalse(exception.getCause().toString().contains("turn-secret-opaque"));
        assertEquals(null, exception.getCause().getCause());
    }

    @Test
    void throwingPersistenceIsSanitizedAndNotRetried() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("throwing-store");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "throwing-store");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new IllegalArgumentException("persistence-secret-opaque");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelStage.INTENT_PERSISTENCE, exception.stage());
        assertEquals(SingleTurnStepKernelProtocolCode.COLLABORATOR_EXCEPTION, exception.code());
        assertEquals(1, repository.persistCalls());
        assertFalse(exception.toString().contains("persistence-secret-opaque"));
        assertFalse(exception.getCause().toString().contains("persistence-secret-opaque"));
    }

    @Test
    void foundPersistenceOutcomeFailsClosedAfterOneWrite() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("found");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "found");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.found(
                        SingleTurnStepKernelTestFixtures.persisted(recovered, request.intent())));

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                exception.code());
        assertEquals(1, repository.persistCalls());
    }

    @Test
    void mismatchedPersistedIntentFailsClosedAfterOneWrite() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("persisted-mismatch");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "persisted-mismatch");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.applied(
                        SingleTurnStepKernelTestFixtures.persisted(
                                recovered,
                                SingleTurnStepKernelTestFixtures.intent(recovered, "other"))));

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_PERSISTED_INTENT,
                exception.code());
        assertEquals(1, repository.persistCalls());
    }
}
