package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.persistence.PersistenceOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepActivationCompositionBoundaryTest {
    @Test
    void requestAndAttemptValidateRequiredAndIdentifierFields() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("boundary-required", false);
        StepActivationCompositionValidationException missing = assertThrows(
                StepActivationCompositionValidationException.class,
                () -> new StepActivationCompositionRequest(null, null, null));
        assertEquals(StepActivationCompositionValidationCode.REQUIRED_VALUE_MISSING,
                missing.code());
        assertThrows(
                StepActivationCompositionValidationException.class,
                () -> new StepActivationAttempt(
                        " ", "token", StepActivationCompositionTestFixtures.T0,
                        seeded.request().attempt().eventDraft(),
                        StepActivationCompositionTestFixtures.T0));
    }

    @Test
    void publicValuesRedactTokensAndIdentityDetails() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("boundary-safe", false);
        String attemptText = seeded.request().attempt().toString();
        String requestText = seeded.request().toString();
        assertFalse(attemptText.contains("token-boundary-safe"));
        assertFalse(requestText.contains("plan-boundary-safe"));
        assertFalse(requestText.contains("owner-boundary-safe"));
    }

    @Test
    void outcomeConstructorsRejectImpossibleStates() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("boundary-outcome", false);
        assertThrows(
                StepActivationCompositionValidationException.class,
                () -> new StepActivationCommitted(
                        PersistenceOutcome.REJECTED,
                        null,
                        StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY));
        assertThrows(
                StepActivationCompositionValidationException.class,
                () -> new StepActivationLeaseRejected(
                        seeded.committed().planId(),
                        new io.paperagent.v2.persistence.PersistenceFailure(
                                io.paperagent.v2.persistence.PersistenceErrorCode.LEASE_HELD,
                                "planId"),
                        StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY));
    }

    @Test
    void protocolSurfaceDoesNotExposeCollaboratorCauseDetails() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("boundary-protocol", false);
        StepActivationCompositionProtocolException failure =
                assertThrows(StepActivationCompositionProtocolException.class,
                        () -> new DefaultStepActivationComposer(
                                request -> { throw new IllegalArgumentException("secret"); },
                                seeded.persistence().leases(),
                                seeded.persistence().stepActivations())
                                .compose(seeded.request()));
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.toString().contains("plan-boundary-protocol"));
    }

    @Test
    void validationAndProtocolPathsAreClosedLexicons() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StepActivationCompositionValidationException(
                        StepActivationCompositionValidationCode.REQUIRED_VALUE_MISSING,
                        "stepActivationComposition.unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StepActivationCompositionProtocolException(
                        new io.paperagent.v2.contracts.PlanId("plan-lexicon"),
                        StepActivationCompositionStage.MATERIALIZE,
                        StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                        "stepActivationComposition.unknown",
                        StepActivationLeaseDisposition.NO_LEASE_ACTION,
                        null));
    }
}
