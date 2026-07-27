package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedStepReplanCompositionBoundaryTest {

    @Test
    void rejectsEveryInputAuthorityMismatchBeforePersistence() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("authority");
        var request = scenario.request();
        var recovered = scenario.recovered();
        var intent = scenario.turnLimitReached().persistedIntents().get(0);

        assertAuthorityRejected(
                scenario,
                new BoundedStepAgentLoopTurnLimitReached(
                        new PlanId("other-plan"),
                        scenario.turnLimitReached().stepId(),
                        1,
                        scenario.turnLimitReached().persistedIntents()),
                request);
        assertAuthorityRejected(
                scenario,
                new BoundedStepAgentLoopTurnLimitReached(
                        scenario.turnLimitReached().planId(),
                        new PlanStepId("other-step"),
                        1,
                        scenario.turnLimitReached().persistedIntents()),
                request);
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        new PlanId("other-plan"),
                        request.leaseToken(),
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        request.activeStepId()));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        request.leaseToken(),
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        new PlanStepId("other-step")));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        "other-token",
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        request.activeStepId()));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        request.leaseToken(),
                        request.fencingToken() + 1,
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        request.activeStepId()));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        request.leaseToken(),
                        request.fencingToken(),
                        new PlanRevisionId("other-revision"),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        request.activeStepId()));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        request.leaseToken(),
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber() + 1,
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        request.activeStepId()));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        request.leaseToken(),
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion() + 1,
                        request.expectedEventHeadSequence(),
                        request.activeStepId()));
        assertAuthorityRejected(
                scenario,
                scenario.turnLimitReached(),
                BoundedStepReplanCompositionTestFixtures.copyRequest(
                        request,
                        request.planId(),
                        request.leaseToken(),
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence() + 1,
                        request.activeStepId()));

        assertAuthorityRejected(
                scenario,
                BoundedStepReplanCompositionTestFixtures.turnLimit(scenario,
                        BoundedStepReplanCompositionTestFixtures.copyIntent(
                                intent,
                                new PlanId("other-plan"),
                                intent.intent().stepId(),
                                intent.leaseOwnerId(),
                                intent.fencingToken(),
                                intent.activationEventId())),
                request);
        assertAuthorityRejected(
                scenario,
                BoundedStepReplanCompositionTestFixtures.turnLimit(scenario,
                        BoundedStepReplanCompositionTestFixtures.copyIntent(
                                intent,
                                intent.intent().planId(),
                                new PlanStepId("other-step"),
                                intent.leaseOwnerId(),
                                intent.fencingToken(),
                                intent.activationEventId())),
                request);
        assertAuthorityRejected(
                scenario,
                BoundedStepReplanCompositionTestFixtures.turnLimit(scenario,
                        BoundedStepReplanCompositionTestFixtures.copyIntent(
                                intent,
                                intent.intent().planId(),
                                intent.intent().stepId(),
                                "other-owner",
                                intent.fencingToken(),
                                intent.activationEventId())),
                request);
        assertAuthorityRejected(
                scenario,
                BoundedStepReplanCompositionTestFixtures.turnLimit(scenario,
                        BoundedStepReplanCompositionTestFixtures.copyIntent(
                                intent,
                                intent.intent().planId(),
                                intent.intent().stepId(),
                                intent.leaseOwnerId(),
                                intent.fencingToken() + 1,
                                intent.activationEventId())),
                request);
        assertAuthorityRejected(
                scenario,
                BoundedStepReplanCompositionTestFixtures.turnLimit(scenario,
                        BoundedStepReplanCompositionTestFixtures.copyIntent(
                                intent,
                                intent.intent().planId(),
                                intent.intent().stepId(),
                                intent.leaseOwnerId(),
                                intent.fencingToken(),
                                new EventId("other-activation"))),
                request);

        var mismatchedLease = new io.paperagent.v2.persistence.LeaseRecord(
                recovered.planId(),
                "other-owner",
                recovered.lease().leaseToken(),
                recovered.lease().fencingToken(),
                recovered.lease().acquiredAt(),
                recovered.lease().expiresAt());
        var mismatchedRecovery = new RecoveredActiveStep(
                recovered.recovery(),
                mismatchedLease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        var repository = repositoryFor(scenario);
        var composer = new DefaultBoundedStepReplanComposer(repository);
        var failure = assertProtocol(() -> composer.compose(
                mismatchedRecovery, scenario.turnLimitReached(), request));
        assertEquals(
                BoundedStepReplanCompositionProtocolCode.INCONSISTENT_REQUEST_AUTHORITY,
                failure.code());
        assertEquals(0, repository.calls());
    }

    @Test
    void rejectsNullInputsBeforePersistence() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("null-input");
        var repository = repositoryFor(scenario);
        var composer = new DefaultBoundedStepReplanComposer(repository);

        assertThrows(BoundedStepReplanCompositionValidationException.class,
                () -> composer.compose(null, scenario.turnLimitReached(), scenario.request()));
        assertThrows(BoundedStepReplanCompositionValidationException.class,
                () -> composer.compose(scenario.recovered(), null, scenario.request()));
        assertThrows(BoundedStepReplanCompositionValidationException.class,
                () -> composer.compose(scenario.recovered(), scenario.turnLimitReached(), null));
        assertEquals(0, repository.calls());
    }

    @Test
    void rejectsRecoveredCheckpointThatDivergesFromActivationAuthority() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario(
                "activation-checkpoint-mismatch");
        var recovered = scenario.recovered();
        var recovery = recovered.recovery();
        var forgedRecovery = new io.paperagent.v2.persistence
                .PersistedStepRecoveryActive(
                        recovery.taskFrame(),
                        recovery.plan(),
                        new io.paperagent.v2.persistence.VersionedCheckpoint(
                                recovery.checkpoint().version() + 1,
                                recovery.checkpoint().checkpoint()),
                        recovery.activation(),
                        recovery.executionContext());
        var forgedRecovered = new RecoveredActiveStep(
                forgedRecovery,
                recovered.lease(),
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        var forgedRequest = BoundedStepReplanCompositionTestFixtures.copyRequest(
                scenario.request(),
                scenario.request().planId(),
                scenario.request().leaseToken(),
                scenario.request().fencingToken(),
                scenario.request().expectedRevisionId(),
                scenario.request().expectedRevisionNumber(),
                scenario.request().expectedCheckpointVersion() + 1,
                scenario.request().expectedEventHeadSequence(),
                scenario.request().activeStepId());
        var repository = repositoryFor(scenario);
        var composer = new DefaultBoundedStepReplanComposer(repository);

        var failure = assertProtocol(() -> composer.compose(
                forgedRecovered, scenario.turnLimitReached(), forgedRequest));

        assertEquals(
                BoundedStepReplanCompositionProtocolCode.INCONSISTENT_REQUEST_AUTHORITY,
                failure.code());
        assertEquals(0, repository.calls());
    }

    @Test
    void failsClosedForNullFoundAndMalformedCollaboratorResults() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("malformed");

        var nullRepository = repositoryFor(scenario);
        nullRepository.returnNull();
        assertProtocol(() -> new DefaultBoundedStepReplanComposer(nullRepository).compose(
                scenario.recovered(), scenario.turnLimitReached(), scenario.request()));

        var foundRepository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(PersistenceResult.found(
                        BoundedStepReplanCompositionTestFixtures.persisted(
                                scenario.recovered(), scenario.request())));
        assertProtocol(() -> new DefaultBoundedStepReplanComposer(foundRepository).compose(
                scenario.recovered(), scenario.turnLimitReached(), scenario.request()));

        var malformedValueRepository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(
                        BoundedStepReplanCompositionTestFixtures.malformedAppliedValue());
        assertProtocol(() -> new DefaultBoundedStepReplanComposer(
                malformedValueRepository).compose(
                        scenario.recovered(), scenario.turnLimitReached(), scenario.request()));

        var malformedFailureRepository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(
                        BoundedStepReplanCompositionTestFixtures.malformedRejectedFailure());
        assertProtocol(() -> new DefaultBoundedStepReplanComposer(
                malformedFailureRepository).compose(
                        scenario.recovered(), scenario.turnLimitReached(), scenario.request()));

        var other = BoundedStepReplanCompositionTestFixtures.scenario("other-result");
        var mismatchedResultRepository = new BoundedStepReplanCompositionTestFixtures
                .RecordingReplanRepository(PersistenceResult.applied(
                        BoundedStepReplanCompositionTestFixtures.persisted(
                                other.recovered(), other.request())));
        assertProtocol(() -> new DefaultBoundedStepReplanComposer(
                mismatchedResultRepository).compose(
                        scenario.recovered(), scenario.turnLimitReached(), scenario.request()));

        assertEquals(1, nullRepository.calls());
        assertEquals(1, foundRepository.calls());
        assertEquals(1, malformedValueRepository.calls());
        assertEquals(1, malformedFailureRepository.calls());
        assertEquals(1, mismatchedResultRepository.calls());
    }

    @Test
    void sanitizesThrownCollaboratorExceptionsWithoutRetry() {
        var scenario = BoundedStepReplanCompositionTestFixtures.scenario("throwing");
        var repository = repositoryFor(scenario);
        repository.throwWith(new IllegalStateException("sensitive collaborator detail"));

        var failure = assertProtocol(() -> new DefaultBoundedStepReplanComposer(repository)
                .compose(scenario.recovered(), scenario.turnLimitReached(), scenario.request()));

        assertEquals(BoundedStepReplanCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                failure.code());
        assertEquals(1, repository.calls());
        assertFalse(failure.getMessage().contains("sensitive collaborator detail"));
        assertTrue(failure.getCause().getMessage().contains("redacted"));
        assertFalse(failure.getCause().getMessage().contains("sensitive collaborator detail"));
    }

    private static void assertAuthorityRejected(
            BoundedStepReplanCompositionTestFixtures.Scenario scenario,
            BoundedStepAgentLoopTurnLimitReached turnLimitReached,
            io.paperagent.v2.persistence.ActiveStepReplanRequest request) {
        var repository = repositoryFor(scenario);
        var composer = new DefaultBoundedStepReplanComposer(repository);
        var failure = assertProtocol(() -> composer.compose(
                scenario.recovered(), turnLimitReached, request));
        assertEquals(
                BoundedStepReplanCompositionProtocolCode.INCONSISTENT_REQUEST_AUTHORITY,
                failure.code());
        assertEquals(0, repository.calls());
    }

    private static BoundedStepReplanCompositionTestFixtures.RecordingReplanRepository
            repositoryFor(BoundedStepReplanCompositionTestFixtures.Scenario scenario) {
        return new BoundedStepReplanCompositionTestFixtures.RecordingReplanRepository(
                PersistenceResult.applied(BoundedStepReplanCompositionTestFixtures.persisted(
                        scenario.recovered(), scenario.request())));
    }

    private static BoundedStepReplanCompositionProtocolException assertProtocol(
            Executable executable) {
        return assertThrows(
                BoundedStepReplanCompositionProtocolException.class,
                executable);
    }
}
