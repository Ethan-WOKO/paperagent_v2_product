package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleTurnStepKernelBoundaryTest {

    @Test
    void publicComponentsRejectNullWithMachineReadableCodes() {
        SingleTurnStepKernelValidationException requestFailure = assertThrows(
                SingleTurnStepKernelValidationException.class,
                () -> new SingleTurnStepKernelRequest(null));
        assertEquals(SingleTurnStepKernelValidationCode.REQUIRED_VALUE_MISSING,
                requestFailure.code());
        assertEquals("singleTurnStepKernelRequest.recoveredStep", requestFailure.path());

        SingleTurnStepKernelValidationException decisionFailure = assertThrows(
                SingleTurnStepKernelValidationException.class,
                () -> new EffectIntentDecision(null));
        assertEquals("effectIntentDecision.intent", decisionFailure.path());

        SingleTurnStepKernelValidationException kernelFailure = assertThrows(
                SingleTurnStepKernelValidationException.class,
                () -> new DefaultSingleTurnStepKernel(
                        null,
                        new SingleTurnStepKernelTestFixtures
                                .RecordingEffectIntentRepository(request -> null)));
        assertEquals("singleTurnStepKernel.stepTurnPort", kernelFailure.path());
    }

    @Test
    void publicToStringsKeepRecoveryIntentAndPersistenceOpaque() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("redaction");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "redaction");
        PersistedEffectIntent persisted = SingleTurnStepKernelTestFixtures.persisted(recovered, intent);

        assertFalse(new SingleTurnStepKernelRequest(recovered).toString()
                .contains(recovered.lease().leaseToken()));
        assertFalse(new StepTurnInput(
                recovered.recovery().taskFrame(),
                recovered.recovery().plan(),
                recovered.recovery().checkpoint(),
                recovered.recovery().plan().latestRevision().steps().get(0)).toString()
                .contains("goal redaction"));
        assertFalse(new EffectIntentDecision(intent).toString().contains("workspace.edit"));
        assertFalse(new SingleTurnIntentPersisted(persisted).toString()
                .contains(recovered.lease().ownerId()));
    }

    @Test
    void inactiveRecoveredCheckpointFailsBeforeTurnAndPersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures
                .recoveredWithInactiveCheckpoint("inactive");
        AtomicInteger turns = new AtomicInteger();
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("invalid recovery must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(input -> {
                    turns.incrementAndGet();
                    return new NoEffectDecision();
                }, repository).run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelStage.RECOVERED_AUTHORITY, exception.stage());
        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_RECOVERED_AUTHORITY,
                exception.code());
        assertEquals(0, turns.get());
        assertEquals(0, repository.persistCalls());
    }

    @Test
    void activationLeaseMismatchFailsBeforeTurnAndPersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures
                .recoveredWithMismatchedActivationLease("lease-mismatch");
        AtomicInteger turns = new AtomicInteger();
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("invalid recovery must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(input -> {
                    turns.incrementAndGet();
                    return new NoEffectDecision();
                }, repository).run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_RECOVERED_AUTHORITY,
                exception.code());
        assertEquals(0, turns.get());
        assertEquals(0, repository.persistCalls());
    }

    @Test
    void snapshotPlanIdMismatchWithCheckpointAndActivationFailsBeforeTurnAndPersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures
                .recoveredWithPlanIdMismatchedFromCheckpointAndActivation("plan-mismatch");
        assertRecoveredAuthorityFailsBeforeTurnAndPersistence(recovered);
    }

    @Test
    void activationStepMissingFromCurrentPlanFailsBeforeTurnAndPersistence() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures
                .recoveredWithActivationStepMissingFromCurrentPlan("missing-current-step");
        assertRecoveredAuthorityFailsBeforeTurnAndPersistence(recovered);
    }

    @Test
    void nullPersistenceResultFailsClosedAfterOneWrite() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("null-persistence");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "null-persistence");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> null);

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelStage.INTENT_PERSISTENCE, exception.stage());
        assertEquals(SingleTurnStepKernelProtocolCode.NULL_COLLABORATOR_RESULT,
                exception.code());
        assertEquals(1, repository.persistCalls());
    }

    @Test
    void persistedLeaseOwnerMismatchFailsClosedAfterOneWrite() {
        RecoveredActiveStep recovered = SingleTurnStepKernelTestFixtures.recovered("owner-mismatch");
        EffectIntent intent = SingleTurnStepKernelTestFixtures.intent(recovered, "owner-mismatch");
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> PersistenceResult.applied(new PersistedEffectIntent(
                        request.intent(),
                        "other-owner",
                        request.fencingToken(),
                        request.expectedActivationEventId())));

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(
                        input -> new EffectIntentDecision(intent), repository)
                        .run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_PERSISTED_INTENT,
                exception.code());
        assertEquals(1, repository.persistCalls());
    }

    private static void assertRecoveredAuthorityFailsBeforeTurnAndPersistence(
            RecoveredActiveStep recovered) {
        AtomicInteger turns = new AtomicInteger();
        var repository = new SingleTurnStepKernelTestFixtures.RecordingEffectIntentRepository(
                request -> {
                    throw new AssertionError("invalid recovery must not persist");
                });

        SingleTurnStepKernelProtocolException exception = assertThrows(
                SingleTurnStepKernelProtocolException.class,
                () -> new DefaultSingleTurnStepKernel(input -> {
                    turns.incrementAndGet();
                    return new NoEffectDecision();
                }, repository).run(new SingleTurnStepKernelRequest(recovered)));

        assertEquals(SingleTurnStepKernelStage.RECOVERED_AUTHORITY, exception.stage());
        assertEquals(SingleTurnStepKernelProtocolCode.INCONSISTENT_RECOVERED_AUTHORITY,
                exception.code());
        assertEquals(0, turns.get());
        assertEquals(0, repository.persistCalls());
    }
}
