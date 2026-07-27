package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationFixture.EXISTING_RECEIPT;
import static io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationFixture.LEASE_TOKEN;
import static io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationFixture.OUTCOME_HASH;
import static io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationFixture.PEER;
import static io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationFixture.T0;
import static io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicActiveStepCompletionMaterializerTest {
    private final DeterministicActiveStepCompletionMaterializer materializer =
            new DeterministicActiveStepCompletionMaterializer();

    @Test
    void materializesExactEffectFreeNonFinalRequest() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        StepCompletionRequest result = materializer.materialize(
                ActiveStepCompletionMaterializationFixture.request(
                        recovered, List.of()));

        PlanRevision current = recovered.recovery().plan().latestRevision();
        assertEquals(recovered.planId(), result.planId());
        assertEquals(LEASE_TOKEN, result.leaseToken());
        assertEquals(7, result.fencingToken());
        assertEquals(current.id(), result.expectedRevisionId());
        assertEquals(current.number(), result.expectedRevisionNumber());
        assertEquals(3, result.expectedCheckpointVersion());
        assertEquals(2, result.expectedEventHeadSequence());
        assertEquals(TARGET, result.stepId());
        assertEquals(TARGET, result.completionFact().stepId());
        assertEquals(OUTCOME_HASH, result.completionFact().outcomeHash());
        assertEquals(List.of(), result.completionFact().receiptReferences());
        assertEquals(3, result.completionEvent().sequence());
        assertEquals(recovered.recovery().taskFrame().id(),
                result.completionEvent().taskFrameId());
        assertEquals(recovered.planId(), result.completionEvent().planId());
        assertEquals(new PlanRevisionId("revision-complete"),
                result.completedRevision().id());
        assertEquals(2, result.completedRevision().number());
        assertEquals(Optional.of(current.id()),
                result.completedRevision().parentRevisionId());
        assertEquals(current.steps(), result.completedRevision().steps());
        assertEquals(Map.of(TARGET, result.completionFact()),
                result.completedRevision().completedFacts());
        assertEquals(PlanExecutionState.ACTIVE,
                result.completedCheckpoint().planState());
        assertEquals(StepExecutionState.SUCCEEDED,
                result.completedCheckpoint().stepStates().get(TARGET));
        assertEquals(StepExecutionState.NOT_STARTED,
                result.completedCheckpoint().stepStates().get(PEER));
        assertEquals(List.of(EXISTING_RECEIPT),
                result.completedCheckpoint().receiptReferences());
    }

    @Test
    void appendsOrderedReceiptEvidenceAndCompletesFinalPlan() {
        List<ReceiptId> receipts = List.of(
                new ReceiptId("receipt-z"),
                new ReceiptId("receipt-a"));
        StepCompletionRequest result = materializer.materialize(
                ActiveStepCompletionMaterializationFixture.request(
                        ActiveStepCompletionMaterializationFixture
                                .finalRecovered(),
                        receipts));

        assertEquals(receipts,
                result.completionFact().receiptReferences());
        assertEquals(
                List.of(
                        EXISTING_RECEIPT,
                        receipts.get(0),
                        receipts.get(1)),
                result.completedCheckpoint().receiptReferences());
        assertEquals(PlanExecutionState.SUCCEEDED,
                result.completedCheckpoint().planState());
        assertTrue(result.completedCheckpoint().stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED));
    }

    @Test
    void preservesPriorFactsStepsPeerStatesAndExistingReceipts() {
        RecoveredActiveStep source =
                ActiveStepCompletionMaterializationFixture.finalRecovered();
        StepCompletionRequest result = materializer.materialize(
                ActiveStepCompletionMaterializationFixture.request(
                        source,
                        List.of(new ReceiptId("receipt-new"))));

        assertEquals(
                source.recovery().plan().latestRevision().steps(),
                result.completedRevision().steps());
        assertEquals(
                source.recovery().checkpoint().checkpoint()
                        .stepStates().get(PEER),
                result.completedCheckpoint().stepStates().get(PEER));
        assertEquals(
                EXISTING_RECEIPT,
                result.completedCheckpoint().receiptReferences().get(0));
        assertEquals(2, result.completedRevision().completedFacts().size());
        assertEquals(
                source.recovery().plan().latestRevision()
                        .completedFacts().get(PEER),
                result.completedRevision().completedFacts().get(PEER));
    }

    @Test
    void permitsAbsentOptionalCausationAndDerivesAllEventBindings() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        ActiveStepCompletionMaterializationRequest request =
                new ActiveStepCompletionMaterializationRequest(
                        recovered,
                        ActiveStepCompletionMaterializationFixture
                                .factDraft(List.of()),
                        ActiveStepCompletionMaterializationFixture
                                .eventDraft(Optional.empty()),
                        ActiveStepCompletionMaterializationFixture
                                .revisionDraft(),
                        T0.plusSeconds(6));
        StepCompletionRequest result = materializer.materialize(request);

        assertEquals(Optional.empty(),
                result.completionEvent().causationId());
        assertEquals(recovered.planId(),
                result.completionEvent().planId());
        assertEquals(recovered.recovery().taskFrame().id(),
                result.completionEvent().taskFrameId());
        assertEquals(3, result.completionEvent().sequence());
    }

    @Test
    void repeatedEqualInputIsStructurallyEqualAndDoesNotRetainMutableLists() {
        List<ReceiptId> mutable = new ArrayList<>();
        mutable.add(new ReceiptId("receipt-new"));
        ActiveStepCompletionMaterializationRequest first =
                ActiveStepCompletionMaterializationFixture.request(
                        ActiveStepCompletionMaterializationFixture.recovered(),
                        mutable);
        ActiveStepCompletionMaterializationRequest second =
                ActiveStepCompletionMaterializationFixture.request(
                        ActiveStepCompletionMaterializationFixture.recovered(),
                        List.copyOf(mutable));
        mutable.clear();

        StepCompletionRequest firstResult = materializer.materialize(first);
        StepCompletionRequest secondResult = materializer.materialize(second);
        assertEquals(firstResult, secondResult);
        assertEquals(1,
                firstResult.completionFact().receiptReferences().size());
        assertNotSame(mutable,
                first.completionFactDraft().receiptReferences());
        assertThrows(UnsupportedOperationException.class,
                () -> first.completionFactDraft()
                        .receiptReferences()
                        .add(new ReceiptId("other")));
    }

    @Test
    void rejectsNullBlankSelfCausingAndNullReceiptDraftsWithTypedCodes() {
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                () -> materializer.materialize(null));
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode.INVALID_TEXT,
                () -> new ActiveStepCompletionFactDraft(
                        " ", T0, List.of()));
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .EVENT_SELF_CAUSATION,
                () -> {
                    EventId eventId = new EventId("self");
                    new ActiveStepCompletionEventDraft(
                            eventId,
                            T0,
                            new io.paperagent.v2.contracts.EventType("done"),
                            Optional.of(eventId),
                            "correlation",
                            new io.paperagent.v2.contracts.InlineEventPayload(
                                    new io.paperagent.v2.contracts.ObjectValue(
                                            Map.of())));
                });
        List<ReceiptId> withNull = new ArrayList<>();
        withNull.add(null);
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                () -> new ActiveStepCompletionFactDraft(
                        "hash", T0, withNull));
    }

    @Test
    void rejectsDuplicateAndOverlappingReceiptEvidence() {
        ReceiptId duplicate = new ReceiptId("duplicate");
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .DUPLICATE_RECEIPT_ID,
                () -> new ActiveStepCompletionFactDraft(
                        "hash",
                        T0,
                        List.of(duplicate, duplicate)));
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .RECEIPT_OVERLAP,
                () -> materializer.materialize(
                        ActiveStepCompletionMaterializationFixture.request(
                                ActiveStepCompletionMaterializationFixture
                                        .recovered(),
                                List.of(EXISTING_RECEIPT))));
    }

    @Test
    void rejectsEveryCallerOwnedTimeRegressionAgainstActiveCheckpoint() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        Instant regressed = T0.plusSeconds(1);
        ActiveStepCompletionFactDraft fact =
                ActiveStepCompletionMaterializationFixture
                        .factDraft(List.of());
        ActiveStepCompletionEventDraft event =
                ActiveStepCompletionMaterializationFixture
                        .eventDraft(Optional.empty());
        ActiveStepCompletionRevisionDraft revision =
                ActiveStepCompletionMaterializationFixture.revisionDraft();

        assertTimeRegression(new ActiveStepCompletionMaterializationRequest(
                recovered,
                new ActiveStepCompletionFactDraft(
                        "hash", regressed, List.of()),
                event,
                revision,
                T0.plusSeconds(6)));
        assertTimeRegression(new ActiveStepCompletionMaterializationRequest(
                recovered,
                fact,
                new ActiveStepCompletionEventDraft(
                        event.id(),
                        regressed,
                        event.type(),
                        event.causationId(),
                        event.correlationId(),
                        event.payload()),
                revision,
                T0.plusSeconds(6)));
        assertTimeRegression(new ActiveStepCompletionMaterializationRequest(
                recovered,
                fact,
                event,
                new ActiveStepCompletionRevisionDraft(
                        revision.id(), revision.reason(), regressed),
                T0.plusSeconds(6)));
        assertTimeRegression(new ActiveStepCompletionMaterializationRequest(
                recovered, fact, event, revision, regressed));
    }

    @Test
    void rejectsRevisionAndEventIdentityReuse() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        ActiveStepCompletionMaterializationRequest valid =
                ActiveStepCompletionMaterializationFixture.request(
                        recovered, List.of());
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .REVISION_ID_REUSE,
                () -> materializer.materialize(
                        new ActiveStepCompletionMaterializationRequest(
                                recovered,
                                valid.completionFactDraft(),
                                valid.eventDraft(),
                                new ActiveStepCompletionRevisionDraft(
                                        recovered.recovery().plan()
                                                .latestRevision().id(),
                                        "reuse",
                                        T0.plusSeconds(5)),
                                valid.checkpointCreatedAt())));
        ActiveStepCompletionEventDraft old = valid.eventDraft();
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .EVENT_ID_CONFLICT,
                () -> materializer.materialize(
                        new ActiveStepCompletionMaterializationRequest(
                                recovered,
                                valid.completionFactDraft(),
                                new ActiveStepCompletionEventDraft(
                                        recovered.recovery().activation()
                                                .activationEvent().id(),
                                        old.occurredAt(),
                                        old.type(),
                                        Optional.empty(),
                                        old.correlationId(),
                                        old.payload()),
                                valid.revisionDraft(),
                                valid.checkpointCreatedAt())));
    }

    @Test
    void translatesMalformedEventBindingMaterialToSanitizedProtocolFailure() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        ActiveStepCompletionEventDraft valid =
                ActiveStepCompletionMaterializationFixture
                        .eventDraft(Optional.empty());
        ActiveStepCompletionMaterializationProtocolException exception =
                assertThrows(
                        ActiveStepCompletionMaterializationProtocolException
                                .class,
                        () -> materializer.materialize(
                                new ActiveStepCompletionMaterializationRequest(
                                        recovered,
                                        ActiveStepCompletionMaterializationFixture
                                                .factDraft(List.of()),
                                        new ActiveStepCompletionEventDraft(
                                                valid.id(),
                                                valid.occurredAt(),
                                                valid.type(),
                                                valid.causationId(),
                                                "invalid correlation",
                                                valid.payload()),
                                        ActiveStepCompletionMaterializationFixture
                                                .revisionDraft(),
                                        T0.plusSeconds(6))));
        assertEquals(
                ActiveStepCompletionMaterializationProtocolCode
                        .CONTRACT_VALIDATION_FAILED,
                exception.code());
        assertEquals(
                ActiveStepCompletionMaterializationStage.EVENT,
                exception.stage());
        assertEquals("completionEvent", exception.path());
        assertFalse(exception.getMessage().contains("invalid correlation"));
    }

    @Test
    void rejectsWrongCheckpointVersionSequenceAndLeaseDisposition() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        Checkpoint current = recovered.recovery().checkpoint().checkpoint();
        assertAuthorityFailure(
                ActiveStepCompletionMaterializationFixture.withCheckpoint(
                        recovered, 4, current));
        Checkpoint wrongSequence =
                ActiveStepCompletionMaterializationFixture.checkpointWith(
                        recovered,
                        3,
                        PlanExecutionState.ACTIVE,
                        current.stepStates());
        assertAuthorityFailure(
                ActiveStepCompletionMaterializationFixture.withCheckpoint(
                        recovered, 3, wrongSequence));
        assertThrows(
                io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryValidationException.class,
                () -> new RecoveredActiveStep(
                        recovered.recovery(),
                        recovered.lease(),
                        StepRecoveryLeaseDisposition.NO_LEASE_ACTION));
    }

    @Test
    void rejectsCrossPlanLeaseActivationAndCheckpointAuthority() {
        RecoveredActiveStep recovered =
                ActiveStepCompletionMaterializationFixture.recovered();
        LeaseRecord crossPlan = new LeaseRecord(
                new io.paperagent.v2.contracts.PlanId("other-plan"),
                recovered.lease().ownerId(),
                "other-token",
                recovered.lease().fencingToken(),
                recovered.lease().acquiredAt(),
                recovered.lease().expiresAt());
        Plan old = recovered.recovery().plan();
        Plan crossBound = new Plan(
                crossPlan.planId(),
                old.taskFrameId(),
                old.revisions());
        assertAuthorityFailure(
                ActiveStepCompletionMaterializationFixture.withPlanAndLease(
                        recovered,
                        crossBound,
                        crossPlan));
    }

    @Test
    void rejectsNoOrMultipleActiveAndDisallowedPeerState() {
        RecoveredActiveStep source =
                ActiveStepCompletionMaterializationFixture.recovered();
        Map<PlanStepId, StepExecutionState> none =
                new LinkedHashMap<>(source.recovery()
                        .checkpoint().checkpoint().stepStates());
        none.put(TARGET, StepExecutionState.NOT_STARTED);
        assertProtocolFailure(
                ActiveStepCompletionMaterializationFixture.withCheckpoint(
                        source,
                        3,
                                ActiveStepCompletionMaterializationFixture
                                        .checkpointWith(
                                                source,
                                                2,
                                                PlanExecutionState.ACTIVE,
                                                none)));

        Map<PlanStepId, StepExecutionState> multiple =
                new LinkedHashMap<>(source.recovery()
                        .checkpoint().checkpoint().stepStates());
        multiple.put(PEER, StepExecutionState.ACTIVE);
        assertProtocolFailure(
                ActiveStepCompletionMaterializationFixture.withCheckpoint(
                        source,
                        3,
                                ActiveStepCompletionMaterializationFixture
                                        .checkpointWith(
                                                source,
                                                2,
                                                PlanExecutionState.ACTIVE,
                                                multiple)));

        Map<PlanStepId, StepExecutionState> paused =
                new LinkedHashMap<>(source.recovery()
                        .checkpoint().checkpoint().stepStates());
        paused.put(PEER, StepExecutionState.PAUSED);
        assertProtocolFailure(
                ActiveStepCompletionMaterializationFixture.withCheckpoint(
                        source,
                        3,
                                ActiveStepCompletionMaterializationFixture
                                        .checkpointWith(
                                                source,
                                                2,
                                                PlanExecutionState.ACTIVE,
                                                paused)));
    }

    @Test
    void rejectsCompletedTargetAndMalformedPlanBinding() {
        RecoveredActiveStep source =
                ActiveStepCompletionMaterializationFixture.recovered();
        assertProtocolFailure(
                ActiveStepCompletionMaterializationFixture.withPlan(
                        source,
                        ActiveStepCompletionMaterializationFixture
                                .withCompletedTarget(source)));
        Plan old = source.recovery().plan();
        Plan crossBound = new Plan(
                new io.paperagent.v2.contracts.PlanId("other-plan"),
                old.taskFrameId(),
                old.revisions());
        LeaseRecord crossBoundLease = new LeaseRecord(
                crossBound.id(),
                source.lease().ownerId(),
                "cross-bound-token",
                source.lease().fencingToken(),
                source.lease().acquiredAt(),
                source.lease().expiresAt());
        assertAuthorityFailure(
                ActiveStepCompletionMaterializationFixture.withPlanAndLease(
                        source, crossBound, crossBoundLease));
    }

    @Test
    void diagnosticsAndStringFormsRedactAllSensitiveMaterial() {
        ActiveStepCompletionMaterializationRequest request =
                ActiveStepCompletionMaterializationFixture.request(
                        ActiveStepCompletionMaterializationFixture.recovered(),
                        List.of(new ReceiptId("secret-receipt")));
        List<String> rendered = List.of(
                request.toString(),
                request.completionFactDraft().toString(),
                request.eventDraft().toString(),
                request.revisionDraft().toString(),
                materializer.materialize(request).toString());
        for (String value : rendered) {
            assertFalse(value.contains(LEASE_TOKEN));
            assertFalse(value.contains(OUTCOME_HASH));
            assertFalse(value.contains("secret-receipt"));
            assertFalse(value.contains("revision-complete"));
        }
        ActiveStepCompletionMaterializationValidationException validation =
                assertThrows(
                        ActiveStepCompletionMaterializationValidationException
                                .class,
                        () -> materializer.materialize(null));
        assertEquals(
                "active-Step completion materialization validation failed",
                validation.getMessage());
        assertFalse(validation.getMessage().contains(LEASE_TOKEN));
        ActiveStepCompletionMaterializationProtocolException protocol =
                assertThrows(
                        ActiveStepCompletionMaterializationProtocolException
                                .class,
                        () -> materializer.materialize(
                                ActiveStepCompletionMaterializationFixture
                                        .request(
                                                ActiveStepCompletionMaterializationFixture
                                                        .withCheckpoint(
                                                                request
                                                                        .recoveredActiveStep(),
                                                                4,
                                                                request
                                                                        .recoveredActiveStep()
                                                                        .recovery()
                                                                        .checkpoint()
                                                                        .checkpoint()),
                                                List.of())));
        assertEquals(
                "active-Step completion materialization protocol failed",
                protocol.getMessage());
        assertFalse(protocol.getMessage().contains(LEASE_TOKEN));
    }

    private void assertTimeRegression(
            ActiveStepCompletionMaterializationRequest request) {
        assertValidation(
                ActiveStepCompletionMaterializationValidationCode
                        .TIME_REGRESSION,
                () -> materializer.materialize(request));
    }

    private void assertAuthorityFailure(RecoveredActiveStep recovered) {
        ActiveStepCompletionMaterializationProtocolException exception =
                assertThrows(
                        ActiveStepCompletionMaterializationProtocolException
                                .class,
                        () -> materializer.materialize(
                                ActiveStepCompletionMaterializationFixture
                                        .request(recovered, List.of())));
        assertEquals(
                ActiveStepCompletionMaterializationProtocolCode
                        .INCONSISTENT_RECOVERED_AUTHORITY,
                exception.code());
        assertEquals(
                ActiveStepCompletionMaterializationStage.RECOVERED_AUTHORITY,
                exception.stage());
    }

    private void assertProtocolFailure(RecoveredActiveStep recovered) {
        assertThrows(
                ActiveStepCompletionMaterializationProtocolException.class,
                () -> materializer.materialize(
                        ActiveStepCompletionMaterializationFixture.request(
                                recovered, List.of())));
    }

    private static void assertValidation(
            ActiveStepCompletionMaterializationValidationCode code,
            org.junit.jupiter.api.function.Executable executable) {
        ActiveStepCompletionMaterializationValidationException exception =
                assertThrows(
                        ActiveStepCompletionMaterializationValidationException
                                .class,
                        executable);
        assertEquals(code, exception.code());
        assertTrue(exception.path() != null && !exception.path().isBlank());
    }
}
