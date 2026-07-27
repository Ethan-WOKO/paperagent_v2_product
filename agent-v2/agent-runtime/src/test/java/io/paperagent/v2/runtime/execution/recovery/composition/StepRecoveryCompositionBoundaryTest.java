package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRecoveryCompositionBoundaryTest {

    @Test
    void publicSurfaceIsNarrowAndHasOneConcreteRecoverer() {
        assertTrue(Modifier.isPublic(StepRecoverer.class.getModifiers()));
        assertTrue(Modifier.isPublic(DefaultStepRecoverer.class.getModifiers()));
        assertTrue(Modifier.isFinal(DefaultStepRecoverer.class.getModifiers()));
        assertEquals(Set.of(
                        RecoveredActiveStep.class,
                        StepRecoveryLeaseRejected.class,
                        StepRecoveryPersistenceRejected.class),
                Set.of(StepRecoveryCompositionOutcome.class.getPermittedSubclasses()));
        assertEquals(1, Arrays.stream(StepRecoverer.class.getMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .count());
        assertEquals(2, DefaultStepRecoverer.class.getConstructors()[0]
                .getParameterCount());
        assertEquals("recover", StepRecoverer.class.getMethods()[0].getName());
    }

    @Test
    void valuesRejectMissingBlankAndImpossibleOutcomeCombinations() {
        StepRecoveryValidationException missing = assertThrows(
                StepRecoveryValidationException.class,
                () -> new StepRecoveryLeaseAttempt(null, "token", null));
        assertEquals(StepRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                missing.code());
        StepRecoveryValidationException blank = assertThrows(
                StepRecoveryValidationException.class,
                () -> new StepRecoveryLeaseAttempt(" ", "token",
                        StepRecoveryCompositionTestFixtures.T0));
        assertEquals(StepRecoveryValidationCode.INVALID_IDENTIFIER, blank.code());
        assertThrows(StepRecoveryValidationException.class,
                () -> new StepRecoveryRequest(null, null));
        assertThrows(StepRecoveryValidationException.class,
                () -> new StepRecoveryLeaseRejected(
                        StepRecoveryCompositionTestFixtures.request("boundary").planId(),
                        new PersistenceFailure(PersistenceErrorCode.LEASE_HELD, "planId"),
                        StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY));
        assertThrows(StepRecoveryValidationException.class,
                () -> new StepRecoveryPersistenceRejected(
                        StepRecoveryCompositionTestFixtures.request("boundary").planId(),
                        StepRecoveryStage.LEASE_ACQUIRE,
                        new PersistenceFailure(
                                PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                                "stepRecovery"),
                        StepRecoveryLeaseDisposition.NOT_ACQUIRED));
        assertThrows(StepRecoveryValidationException.class,
                () -> new StepRecoveryPersistenceRejected(
                        StepRecoveryCompositionTestFixtures.request("boundary").planId(),
                        StepRecoveryStage.INITIAL_INSPECT,
                        new PersistenceFailure(PersistenceErrorCode.LEASE_HELD, "planId"),
                        StepRecoveryLeaseDisposition.NO_LEASE_ACTION));
    }

    @Test
    void valuesAndProtocolStringsRedactLeaseAndOpaqueCollaboratorData() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("redaction");
        var active = StepRecoveryCompositionTestFixtures.active(
                "redaction", "snapshot", false);
        var lease = StepRecoveryCompositionTestFixtures.matchingLease(request, 1);

        assertFalse(request.toString().contains("plan-redaction"));
        assertFalse(request.leaseAttempt().toString().contains("owner-redaction"));
        assertFalse(request.leaseAttempt().toString().contains("token-redaction"));
        assertFalse(new RecoveredActiveStep(
                active, lease, StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY)
                .toString().contains("token-redaction"));

        StepRecoveryProtocolException exception = new StepRecoveryProtocolException(
                request.planId(),
                StepRecoveryStage.LEASE_ACQUIRE,
                StepRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "stepRecoveryComposition.leaseAcquireResult",
                StepRecoveryLeaseDisposition.ACQUISITION_INDETERMINATE,
                new IllegalArgumentException(
                        "owner-redaction token-redaction opaque-value"));
        assertFalse(exception.toString().contains("owner-redaction"));
        assertFalse(exception.toString().contains("token-redaction"));
        assertFalse(exception.getCause().toString().contains("opaque-value"));
        assertEquals(null, exception.getCause().getCause());
    }

    @Test
    void protocolAndValidationPathsAreClosedLexicons() {
        assertThrows(IllegalArgumentException.class,
                () -> new StepRecoveryValidationException(
                        StepRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                        "stepRecoveryComposition.unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> new StepRecoveryProtocolException(
                        StepRecoveryCompositionTestFixtures.request("lexicon").planId(),
                        StepRecoveryStage.INITIAL_INSPECT,
                        StepRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                        "stepRecoveryComposition.unknown",
                        StepRecoveryLeaseDisposition.NO_LEASE_ACTION,
                        null));
    }
}
