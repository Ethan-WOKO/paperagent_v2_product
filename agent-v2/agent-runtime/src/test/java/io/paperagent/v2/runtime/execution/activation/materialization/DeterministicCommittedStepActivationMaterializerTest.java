package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.EventValidators;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ViolationCode;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCommittedStepActivationMaterializerTest {
    private static final String REQUEST_PATH =
            "committedStepActivationMaterializationRequest";

    @Test
    void directRequiredFieldsAndBlankIdentifierUseFrozenPaths() {
        StepActivationEventDraft draft =
                CommittedStepActivationMaterializationFixture
                        .eventDraft("required");
        PersistedExecutionStartCommitted committed =
                CommittedStepActivationMaterializationFixture
                        .committed("required");
        PlanStepId stepId = committed.currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        Instant checkpointTime =
                CommittedStepActivationMaterializationFixture.T0
                        .plusSeconds(2);

        assertDraftFailure(
                null,
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload(),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "stepActivationEventDraft.id");
        assertDraftFailure(
                draft.id(),
                null,
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload(),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "stepActivationEventDraft.occurredAt");
        assertDraftFailure(
                draft.id(),
                draft.occurredAt(),
                null,
                draft.causationId(),
                draft.correlationId(),
                draft.payload(),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "stepActivationEventDraft.type");
        assertDraftFailure(
                draft.id(),
                draft.occurredAt(),
                draft.type(),
                null,
                draft.correlationId(),
                draft.payload(),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "stepActivationEventDraft.causationId");
        assertDraftFailure(
                draft.id(),
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                null,
                draft.payload(),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "stepActivationEventDraft.correlationId");
        assertDraftFailure(
                draft.id(),
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                " ",
                draft.payload(),
                CommittedStepActivationMaterializationValidationCode
                        .INVALID_IDENTIFIER,
                "stepActivationEventDraft.correlationId");
        assertDraftFailure(
                draft.id(),
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                null,
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "stepActivationEventDraft.payload");

        assertRequestFailure(
                null,
                stepId,
                draft,
                checkpointTime,
                REQUEST_PATH + ".committedStart");
        assertRequestFailure(
                committed,
                null,
                draft,
                checkpointTime,
                REQUEST_PATH + ".stepId");
        assertRequestFailure(
                committed,
                stepId,
                null,
                checkpointTime,
                REQUEST_PATH + ".eventDraft");
        assertRequestFailure(
                committed,
                stepId,
                draft,
                null,
                REQUEST_PATH + ".checkpointCreatedAt");

        MaterializedStepActivation materialized =
                CommittedStepActivationMaterializationFixture
                        .materialized("required");
        assertFailure(
                () -> new MaterializedStepActivation(
                        null,
                        materialized.activatedCheckpoint()),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "materializedStepActivation.activationEvent");
        assertFailure(
                () -> new MaterializedStepActivation(
                        materialized.activationEvent(),
                        null),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "materializedStepActivation.activatedCheckpoint");
    }

    @Test
    void publicRecordTextIsExactAndOpaque() {
        String sentinel = "sentinel-sensitive-materialization";
        StepActivationEventDraft draft =
                CommittedStepActivationMaterializationFixture
                        .eventDraft(sentinel);
        CommittedStepActivationMaterializationRequest request =
                CommittedStepActivationMaterializationFixture
                        .request(sentinel);
        MaterializedStepActivation materialized =
                CommittedStepActivationMaterializationFixture
                        .materialized(sentinel);

        assertEquals(
                "StepActivationEventDraft[id=<provided>, "
                        + "occurredAt=<provided>, type=<provided>, "
                        + "causationId=<provided>, "
                        + "correlationId=<provided>, payload=<provided>]",
                draft.toString());
        assertEquals(
                "CommittedStepActivationMaterializationRequest"
                        + "[committedStart=<provided>, stepId=<provided>, "
                        + "eventDraft=<provided>, "
                        + "checkpointCreatedAt=<provided>]",
                request.toString());
        assertEquals(
                "MaterializedStepActivation"
                        + "[activationEvent=<provided>, "
                        + "activatedCheckpoint=<provided>]",
                materialized.toString());
        for (String text : List.of(
                draft.toString(),
                request.toString(),
                materialized.toString())) {
            assertFalse(text.contains(sentinel));
            assertFalse(text.contains("owner-"));
            assertFalse(text.contains("2026-"));
        }
    }

    @Test
    void validationFailuresAreValueFreeAndHaveNoCause() {
        String sentinel = "sentinel-secret-value";
        CommittedStepActivationMaterializationValidationException failure =
                assertThrows(
                        CommittedStepActivationMaterializationValidationException
                                .class,
                        () -> new StepActivationEventDraft(
                                new EventId("event-" + sentinel),
                                CommittedStepActivationMaterializationFixture.T0,
                                new EventType("type-" + sentinel),
                                Optional.empty(),
                                " ",
                                CommittedStepActivationMaterializationFixture
                                        .eventDraft("payload")
                                        .payload()));

        assertEquals(
                CommittedStepActivationMaterializationValidationCode
                        .INVALID_IDENTIFIER,
                failure.code());
        assertEquals(
                "stepActivationEventDraft.correlationId",
                failure.path());
        assertEquals("identifier must not be blank", failure.getMessage());
        assertNull(failure.getCause());
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertFalse(failure.toString().contains(sentinel));
        assertFalse(stack.toString().contains(sentinel));
    }

    @Test
    void nullMethodRequestUsesFrozenTuple() {
        var failure = assertThrows(
                CommittedStepActivationMaterializationValidationException.class,
                () -> new DeterministicCommittedStepActivationMaterializer()
                        .materialize(null));

        assertEquals(
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                failure.code());
        assertEquals(REQUEST_PATH, failure.path());
        assertEquals("value is required", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void materializesSingleRootAndCallerSelectedSecondRootExactly() {
        PersistedExecutionStartCommitted single =
                CommittedStepActivationMaterializationFixture
                        .singleRootCommitted("single");
        PlanStepId singleStep = single.currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        CommittedStepActivationMaterializationRequest singleRequest =
                request(
                        single,
                        singleStep,
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("single"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2));
        MaterializedStepActivation singleResult =
                materializer().materialize(singleRequest);
        assertExactCandidate(singleRequest, singleResult);

        PersistedExecutionStartCommitted twoRootCommitted =
                CommittedStepActivationMaterializationFixture
                        .committed("second-root");
        PlanStepId firstRoot = twoRootCommitted
                .currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        PlanStepId secondRoot = twoRootCommitted
                .currentPlan()
                .latestRevision()
                .steps()
                .get(1)
                .id();
        EventType firstRootBiasedType = new EventType(
                "activate-first-next-" + firstRoot.value());
        Optional<EventId> firstRootBiasedCause = Optional.of(
                new EventId("cause-first-next-" + firstRoot.value()));
        String firstRootBiasedCorrelation =
                "first.next:" + firstRoot.value();
        InlineEventPayload firstRootBiasedPayload =
                new InlineEventPayload(new ObjectValue(Map.of(
                        "instruction",
                        new TextValue(
                                "select first/next " + firstRoot.value()))));
        StepActivationEventDraft adversarialDraft =
                new StepActivationEventDraft(
                        new EventId("event-explicit-second-root"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2),
                        firstRootBiasedType,
                        firstRootBiasedCause,
                        firstRootBiasedCorrelation,
                        firstRootBiasedPayload);
        CommittedStepActivationMaterializationRequest twoRootRequest =
                request(
                        twoRootCommitted,
                        secondRoot,
                        adversarialDraft,
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2));
        assertEquals(secondRoot, twoRootRequest.stepId());

        MaterializedStepActivation twoRootResult =
                materializer().materialize(twoRootRequest);
        assertExactCandidate(twoRootRequest, twoRootResult);
        assertEquals(
                StepExecutionState.NOT_STARTED,
                twoRootResult.activatedCheckpoint()
                        .stepStates()
                        .get(firstRoot));
        assertEquals(
                StepExecutionState.ACTIVE,
                twoRootResult.activatedCheckpoint()
                        .stepStates()
                        .get(secondRoot));
        assertSame(
                firstRootBiasedType,
                twoRootResult.activationEvent().type());
        assertSame(
                firstRootBiasedCause,
                twoRootResult.activationEvent().causationId());
        assertSame(
                firstRootBiasedCorrelation,
                twoRootResult.activationEvent().correlationId());
        assertSame(
                firstRootBiasedPayload,
                twoRootResult.activationEvent().payload());
    }

    @Test
    void sourceBackedAndSourceLessSnapshotsUseTheSamePureBoundary() {
        PersistedExecutionStartCommitted sourceLess =
                CommittedStepActivationMaterializationFixture
                        .committed("source-less", false);
        PersistedExecutionStartCommitted sourceBacked =
                CommittedStepActivationMaterializationFixture
                        .committed("source-backed", true);

        assertTrue(sourceLess.bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .isEmpty());
        assertTrue(sourceBacked.bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .isPresent());

        for (PersistedExecutionStartCommitted committed
                : List.of(sourceLess, sourceBacked)) {
            PlanStepId selected = committed.currentPlan()
                    .latestRevision()
                    .steps()
                    .get(0)
                    .id();
            CommittedStepActivationMaterializationRequest request =
                    request(
                            committed,
                            selected,
                            CommittedStepActivationMaterializationFixture
                                    .eventDraft(selected.value()),
                            committed.executionStart()
                                    .startedCheckpoint()
                                    .checkpoint()
                                    .createdAt());
            assertExactCandidate(
                    request,
                    materializer().materialize(request));
        }
    }

    @Test
    void unknownAndDependencyBearingTargetsUseExactEligibilityTupleFirst() {
        PersistedExecutionStartCommitted committed =
                CommittedStepActivationMaterializationFixture
                        .committed("eligibility");
        PlanStepId dependent = committed.currentPlan()
                .latestRevision()
                .steps()
                .get(2)
                .id();
        EventId self = new EventId("event-self-eligibility");
        StepActivationEventDraft invalidEvent =
                new StepActivationEventDraft(
                        self,
                        CommittedStepActivationMaterializationFixture.T0,
                        new EventType("opaque-eligibility"),
                        Optional.of(self),
                        "contains whitespace",
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("eligibility")
                                .payload());

        assertEligibilityFailure(request(
                committed,
                dependent,
                invalidEvent,
                CommittedStepActivationMaterializationFixture.T0));

        String sentinel = "sentinel-secret-unknown-step";
        var failure = assertEligibilityFailure(request(
                committed,
                new PlanStepId(sentinel),
                invalidEvent,
                CommittedStepActivationMaterializationFixture.T0));
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertFalse(failure.toString().contains(sentinel));
        assertFalse(stack.toString().contains(sentinel));
    }

    @Test
    void eventFailuresPrecedeCheckpointFailureAndContractsPropagate() {
        String selfSentinel = "sentinel-self-sensitive";
        String correlationSentinel =
                "sentinel-correlation-sensitive";
        String timeSentinel = "sentinel-time-sensitive";
        List<String> allSentinels = List.of(
                selfSentinel,
                correlationSentinel,
                timeSentinel);
        PersistedExecutionStartCommitted committed =
                CommittedStepActivationMaterializationFixture
                        .committed("precedence");
        PlanStepId selected = committed.currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        EventId self = new EventId(
                "event-" + selfSentinel);
        StepActivationEventDraft selfCausing =
                new StepActivationEventDraft(
                        self,
                        CommittedStepActivationMaterializationFixture.T0,
                        new EventType("opaque-self"),
                        Optional.of(self),
                        "correlation-self",
                        new InlineEventPayload(new ObjectValue(Map.of(
                                "sensitive",
                                new TextValue(selfSentinel)))));

        assertContractFailure(
                () -> materializer().materialize(request(
                        committed,
                        selected,
                        selfCausing,
                        CommittedStepActivationMaterializationFixture.T0)),
                ViolationCode.INCONSISTENT_REFERENCE,
                "event.causationId",
                "an event cannot cause itself",
                allSentinels);

        StepActivationEventDraft invalidCorrelation =
                new StepActivationEventDraft(
                        new EventId("event-invalid-correlation"),
                        CommittedStepActivationMaterializationFixture.T0,
                        new EventType("opaque-correlation"),
                        Optional.empty(),
                        correlationSentinel + " contains whitespace",
                        new InlineEventPayload(new ObjectValue(Map.of(
                                "sensitive",
                                new TextValue(correlationSentinel)))));
        assertContractFailure(
                () -> materializer().materialize(request(
                        committed,
                        selected,
                        invalidCorrelation,
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2))),
                ViolationCode.INVALID_ID,
                "event.correlationId",
                "ID contains unsupported characters",
                allSentinels);

        StepActivationEventDraft valid = new StepActivationEventDraft(
                new EventId("event-" + timeSentinel),
                CommittedStepActivationMaterializationFixture.T0,
                new EventType("type-" + timeSentinel),
                Optional.empty(),
                "correlation-" + timeSentinel,
                new InlineEventPayload(new ObjectValue(Map.of(
                        "sensitive",
                        new TextValue(timeSentinel)))));
        assertContractFailure(
                () -> materializer().materialize(request(
                        committed,
                        selected,
                        valid,
                        CommittedStepActivationMaterializationFixture.T0)),
                ViolationCode.CHECKPOINT_TIME_REGRESSION,
                "checkpoint.createdAt",
                "checkpoint creation time cannot regress",
                allSentinels);

        Instant equalTime = committed.executionStart()
                .startedCheckpoint()
                .checkpoint()
                .createdAt();
        assertExactCandidate(
                request(committed, selected, valid, equalTime),
                materializer().materialize(
                        request(committed, selected, valid, equalTime)));
    }

    @Test
    void nonCanonicalNestedBootstrapInitialIsNeverObserved() {
        PersistedExecutionStartCommitted canonical =
                CommittedStepActivationMaterializationFixture
                        .committed("nested");
        PersistedExecutionStartCommitted nonCanonicalNested =
                CommittedStepActivationMaterializationFixture
                        .committedWithNonCanonicalNestedInitial("nested");
        assertFalse(CheckpointValidators.validate(
                nonCanonicalNested.bootstrap()
                        .initialCheckpoint()
                        .checkpoint(),
                nonCanonicalNested.bootstrap().taskFrame(),
                nonCanonicalNested.currentPlan(),
                null).isEmpty());

        PlanStepId selected = canonical.currentPlan()
                .latestRevision()
                .steps()
                .get(1)
                .id();
        StepActivationEventDraft draft =
                CommittedStepActivationMaterializationFixture
                        .eventDraft("nested");
        Instant checkpointTime =
                CommittedStepActivationMaterializationFixture.T0
                        .plusSeconds(2);
        MaterializedStepActivation canonicalResult =
                materializer().materialize(request(
                        canonical,
                        selected,
                        draft,
                        checkpointTime));
        MaterializedStepActivation nonCanonicalResult =
                materializer().materialize(request(
                        nonCanonicalNested,
                        selected,
                        draft,
                        checkpointTime));

        assertEquals(canonicalResult, nonCanonicalResult);
        assertExactCandidate(
                request(
                        nonCanonicalNested,
                        selected,
                        draft,
                        checkpointTime),
                nonCanonicalResult);
    }

    @Test
    void opaqueMetadataAndNestedPayloadRemainImmutable() {
        List<ContractValue> mutableItems = new ArrayList<>();
        mutableItems.add(new TextValue("first"));
        ListValue immutableItems = new ListValue(mutableItems);
        Map<String, ContractValue> mutableObject =
                new LinkedHashMap<>();
        mutableObject.put("items", immutableItems);
        InlineEventPayload payload =
                new InlineEventPayload(new ObjectValue(mutableObject));

        PersistedExecutionStartCommitted committed =
                CommittedStepActivationMaterializationFixture
                        .committed("opaque");
        PlanStepId selected = committed.currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        EventId cause = new EventId("event-arbitrary-cause");
        StepActivationEventDraft draft = new StepActivationEventDraft(
                new EventId("event-opaque-activation"),
                CommittedStepActivationMaterializationFixture.T0
                        .minusSeconds(200),
                new EventType("arbitrary.type:v7"),
                Optional.of(cause),
                "opaque:corr.v7",
                payload);
        CommittedStepActivationMaterializationRequest request =
                request(
                        committed,
                        selected,
                        draft,
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2));
        MaterializedStepActivation result =
                materializer().materialize(request);

        assertExactCandidate(request, result);
        assertSame(payload, result.activationEvent().payload());
        assertEquals(Optional.of(cause),
                result.activationEvent().causationId());
        assertFalse(result.activationEvent().causationId().equals(
                Optional.of(committed.executionStart().startEvent().id())));

        mutableItems.add(new TextValue("mutated"));
        mutableObject.put("late", new TextValue("mutated"));
        ObjectValue retained =
                ((InlineEventPayload) result.activationEvent().payload())
                        .value();
        assertEquals(List.of("items"),
                retained.values().keySet().stream().toList());
        assertEquals(
                List.of(new TextValue("first")),
                ((ListValue) retained.values().get("items")).values());
        assertThrows(
                UnsupportedOperationException.class,
                () -> retained.values().put(
                        "forbidden",
                        new TextValue("mutation")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((ListValue) retained.values().get("items"))
                        .values()
                        .add(new TextValue("mutation")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.activatedCheckpoint()
                        .stepStates()
                        .put(selected, StepExecutionState.SUCCEEDED));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.activatedCheckpoint()
                        .receiptReferences()
                        .add(new ReceiptId("receipt-forbidden")));
    }

    @Test
    void repeatedCrossInstanceInterleavedAndConcurrentCallsAreDeterministic() {
        CommittedStepActivationMaterializationRequest requestA =
                CommittedStepActivationMaterializationFixture
                        .request("deterministic-a");
        CommittedStepActivationMaterializationRequest equalRequestA =
                CommittedStepActivationMaterializationFixture
                        .request("deterministic-a");
        CommittedStepActivationMaterializationRequest requestB =
                CommittedStepActivationMaterializationFixture
                        .request("deterministic-b");
        assertEquals(requestA, equalRequestA);

        CommittedStepActivationMaterializer first = materializer();
        CommittedStepActivationMaterializer second = materializer();
        MaterializedStepActivation expectedA =
                first.materialize(requestA);
        MaterializedStepActivation expectedB =
                first.materialize(requestB);
        assertEquals(expectedA, first.materialize(requestA));
        assertEquals(expectedB, first.materialize(requestB));
        assertEquals(expectedA, first.materialize(requestA));
        assertEquals(expectedA, second.materialize(equalRequestA));
        assertNotSame(
                expectedA.activatedCheckpoint().stepStates(),
                requestA.committedStart()
                        .executionStart()
                        .startedCheckpoint()
                        .checkpoint()
                        .stepStates());

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<Callable<MaterializedStepActivation>> calls =
                    new ArrayList<>();
            for (int index = 0; index < 48; index++) {
                calls.add(() -> first.materialize(requestA));
            }
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                var futures = executor.invokeAll(
                        calls,
                        5,
                        TimeUnit.SECONDS);
                for (var future : futures) {
                    assertFalse(future.isCancelled());
                    assertEquals(expectedA, future.get());
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(
                        5,
                        TimeUnit.SECONDS));
            }
        });
    }

    @Test
    void sourceLessCandidateAppliesAndExactRequestReplaysInRealPersistence() {
        PersistenceScenario scenario =
                persistedScenario("compat-source-less", false);
        assertTrue(scenario.committed()
                .bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .isEmpty());
        PlanStepId selected = scenario.committed()
                .currentPlan()
                .latestRevision()
                .steps()
                .get(1)
                .id();
        CommittedStepActivationMaterializationRequest materializationRequest =
                request(
                        scenario.committed(),
                        selected,
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("compat-source-less"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2));
        MaterializedStepActivation materialized =
                materializer().materialize(materializationRequest);
        StepActivationRequest persistenceRequest = persistenceRequest(
                scenario,
                materializationRequest,
                materialized);

        PersistenceResult<PersistedStepActivation> applied =
                scenario.persistence()
                        .stepActivations()
                        .activate(persistenceRequest);
        PersistedStepActivation persisted =
                requireOutcome(applied, PersistenceOutcome.APPLIED);
        assertEquals(scenario.committed().currentPlan().id(),
                persisted.planId());
        assertEquals(selected, persisted.stepId());
        assertEquals(scenario.lease().ownerId(),
                persisted.leaseOwnerId());
        assertEquals(scenario.lease().fencingToken(),
                persisted.fencingToken());
        assertEquals(materialized.activationEvent(),
                persisted.activationEvent());
        assertEquals(
                new VersionedCheckpoint(
                        3,
                        materialized.activatedCheckpoint()),
                persisted.activatedCheckpoint());

        PersistenceResult<PersistedStepActivation> replayed =
                scenario.persistence()
                        .stepActivations()
                        .activate(persistenceRequest);
        assertEquals(
                persisted,
                requireOutcome(replayed, PersistenceOutcome.REPLAYED));
    }

    @Test
    void secondProposalFromOldH0FailsAtExactCheckpointVersionCas() {
        PersistenceScenario scenario =
                persistedScenario("compat-stale", false);
        PlanStepId firstRoot = scenario.committed()
                .currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        CommittedStepActivationMaterializationRequest firstRequest =
                request(
                        scenario.committed(),
                        firstRoot,
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("compat-stale-first"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2));
        MaterializedStepActivation first =
                materializer().materialize(firstRequest);
        requireOutcome(
                scenario.persistence().stepActivations().activate(
                        persistenceRequest(
                                scenario,
                                firstRequest,
                                first)),
                PersistenceOutcome.APPLIED);

        PlanStepId secondRoot = scenario.committed()
                .currentPlan()
                .latestRevision()
                .steps()
                .get(1)
                .id();
        CommittedStepActivationMaterializationRequest staleRequest =
                request(
                        scenario.committed(),
                        secondRoot,
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("compat-stale-second"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(3));
        MaterializedStepActivation staleCandidate =
                materializer().materialize(staleRequest);
        assertExactCandidate(staleRequest, staleCandidate);

        assertPersistenceFailure(
                scenario.persistence().stepActivations().activate(
                        persistenceRequest(
                                scenario,
                                staleRequest,
                                staleCandidate)),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");
    }

    @Test
    void sourceBackedMaterializationDoesNotBypassOrChangeContextAdmission() {
        PersistenceScenario scenario =
                persistedScenario("compat-source-backed", true);
        assertTrue(scenario.committed()
                .bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .isPresent());
        PersistenceResult<?> contextBefore = scenario.persistence()
                .planExecutionContexts()
                .inspect(scenario.committed().currentPlan().id());
        assertPersistenceFailure(
                contextBefore,
                PersistenceErrorCode.NOT_FOUND,
                "planExecutionContext");

        PlanStepId selected = scenario.committed()
                .currentPlan()
                .latestRevision()
                .steps()
                .get(0)
                .id();
        CommittedStepActivationMaterializationRequest materializationRequest =
                request(
                        scenario.committed(),
                        selected,
                        CommittedStepActivationMaterializationFixture
                                .eventDraft("compat-source-backed"),
                        CommittedStepActivationMaterializationFixture.T0
                                .plusSeconds(2));
        MaterializedStepActivation materialized =
                materializer().materialize(materializationRequest);
        assertExactCandidate(materializationRequest, materialized);

        PersistenceResult<?> contextAfter = scenario.persistence()
                .planExecutionContexts()
                .inspect(scenario.committed().currentPlan().id());
        assertEquals(contextBefore, contextAfter);
        assertPersistenceFailure(
                contextAfter,
                PersistenceErrorCode.NOT_FOUND,
                "planExecutionContext");
        assertPersistenceFailure(
                scenario.persistence().stepActivations().activate(
                        persistenceRequest(
                                scenario,
                                materializationRequest,
                                materialized)),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");
    }

    @Test
    void fixtureCarriesExactCommittedH0() {
        PersistedExecutionStartCommitted committed =
                CommittedStepActivationMaterializationFixture
                        .committed("h0", true);
        Checkpoint h0 = committed.executionStart()
                .startedCheckpoint()
                .checkpoint();

        assertEquals(1, committed.executionStart().startEvent().sequence());
        assertEquals(2, committed.executionStart()
                .startedCheckpoint()
                .version());
        assertEquals(1, h0.lastEventSequence());
        assertEquals(PlanExecutionState.ACTIVE, h0.planState());
        assertTrue(h0.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.NOT_STARTED));
        assertTrue(committed.currentPlan()
                .latestRevision()
                .completedFacts()
                .isEmpty());
        assertTrue(h0.receiptReferences().isEmpty());
        assertTrue(committed.bootstrap()
                .taskFrame()
                .sourceProjectVersion()
                .isPresent());
    }

    private static PersistenceScenario persistedScenario(
            String suffix,
            boolean sourceBacked) {
        PersistedExecutionStartCommitted fixture =
                CommittedStepActivationMaterializationFixture
                        .committed(suffix, sourceBacked);
        InMemoryPersistence persistence = new InMemoryPersistence(
                Clock.fixed(
                        CommittedStepActivationMaterializationFixture.T0,
                        ZoneOffset.UTC));
        var persistedBootstrap = requireOutcome(
                persistence.planBootstraps().bootstrap(
                        fixture.bootstrap().taskFrame(),
                        fixture.currentPlan(),
                        fixture.bootstrap()
                                .initialCheckpoint()
                                .checkpoint()),
                PersistenceOutcome.APPLIED);
        String leaseToken = "lease-token-" + suffix;
        LeaseRecord lease = requireOutcome(
                persistence.leases().acquire(
                        fixture.currentPlan().id(),
                        fixture.executionStart().leaseOwnerId(),
                        leaseToken,
                        CommittedStepActivationMaterializationFixture.T0
                                .plus(Duration.ofMinutes(5))),
                PersistenceOutcome.APPLIED);
        var persistedStart = requireOutcome(
                persistence.executionStarts().start(
                        new ExecutionStartRequest(
                                fixture.currentPlan().id(),
                                leaseToken,
                                lease.fencingToken(),
                                fixture.executionStart().startEvent(),
                                fixture.executionStart()
                                        .startedCheckpoint()
                                        .checkpoint())),
                PersistenceOutcome.APPLIED);
        var inspected = requireOutcome(
                persistence.executionStartRecovery().inspect(
                        fixture.currentPlan().id()),
                PersistenceOutcome.FOUND);
        PersistedExecutionStartCommitted committed =
                assertInstanceOf(
                        PersistedExecutionStartCommitted.class,
                        inspected);
        assertEquals(persistedBootstrap, committed.bootstrap());
        assertEquals(fixture.currentPlan(), committed.currentPlan());
        assertEquals(persistedStart, committed.executionStart());
        return new PersistenceScenario(
                persistence,
                committed,
                lease,
                leaseToken);
    }

    private static StepActivationRequest persistenceRequest(
            PersistenceScenario scenario,
            CommittedStepActivationMaterializationRequest
                    materializationRequest,
            MaterializedStepActivation materialized) {
        Checkpoint h0 = scenario.committed()
                .executionStart()
                .startedCheckpoint()
                .checkpoint();
        return new StepActivationRequest(
                scenario.committed().currentPlan().id(),
                scenario.leaseToken(),
                scenario.lease().fencingToken(),
                h0.revisionId(),
                h0.revisionNumber(),
                scenario.committed()
                        .executionStart()
                        .startedCheckpoint()
                        .version(),
                scenario.committed()
                        .executionStart()
                        .startEvent()
                        .sequence(),
                materializationRequest.stepId(),
                materialized.activationEvent(),
                materialized.activatedCheckpoint());
    }

    private static <T> T requireOutcome(
            PersistenceResult<T> result,
            PersistenceOutcome outcome) {
        assertEquals(outcome, result.outcome());
        assertTrue(result.failure().isEmpty());
        return result.value().orElseThrow();
    }

    private static void assertPersistenceFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertTrue(result.value().isEmpty());
        var failure = result.failure().orElseThrow();
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }

    private static void assertExactCandidate(
            CommittedStepActivationMaterializationRequest request,
            MaterializedStepActivation result) {
        PersistedExecutionStartCommitted committed =
                request.committedStart();
        Checkpoint h0 = committed.executionStart()
                .startedCheckpoint()
                .checkpoint();
        EventEnvelope event = result.activationEvent();
        StepActivationEventDraft draft = request.eventDraft();

        assertSame(draft.id(), event.id());
        assertEquals(
                committed.bootstrap().taskFrame().id(),
                event.taskFrameId());
        assertEquals(committed.currentPlan().id(), event.planId());
        assertEquals(2, event.sequence());
        assertSame(draft.occurredAt(), event.occurredAt());
        assertSame(draft.type(), event.type());
        assertSame(draft.causationId(), event.causationId());
        assertSame(draft.correlationId(), event.correlationId());
        assertSame(draft.payload(), event.payload());

        Checkpoint checkpoint = result.activatedCheckpoint();
        assertEquals(h0.taskFrameId(), checkpoint.taskFrameId());
        assertEquals(h0.planId(), checkpoint.planId());
        assertEquals(h0.revisionId(), checkpoint.revisionId());
        assertEquals(h0.revisionNumber(), checkpoint.revisionNumber());
        assertEquals(2, checkpoint.lastEventSequence());
        assertEquals(PlanExecutionState.ACTIVE, checkpoint.planState());
        assertEquals(h0.stepStates().keySet(),
                checkpoint.stepStates().keySet());
        h0.stepStates().forEach((stepId, state) ->
                assertEquals(
                        stepId.equals(request.stepId())
                                ? StepExecutionState.ACTIVE
                                : state,
                        checkpoint.stepStates().get(stepId)));
        assertEquals(
                StepExecutionState.NOT_STARTED,
                h0.stepStates().get(request.stepId()));
        assertEquals(
                h0.receiptReferences(),
                checkpoint.receiptReferences());
        assertSame(request.checkpointCreatedAt(),
                checkpoint.createdAt());
        assertEquals(
                Map.of(),
                committed.currentPlan()
                        .latestRevision()
                        .completedFacts());

        EventValidators.requireNext(
                committed.executionStart().startEvent(),
                event);
        CheckpointValidators.requireValid(
                checkpoint,
                committed.bootstrap().taskFrame(),
                committed.currentPlan(),
                h0);
    }

    private static CommittedStepActivationMaterializationValidationException
            assertEligibilityFailure(
                    CommittedStepActivationMaterializationRequest request) {
        var failure = assertThrows(
                CommittedStepActivationMaterializationValidationException
                        .class,
                () -> materializer().materialize(request));
        assertEquals(
                CommittedStepActivationMaterializationValidationCode
                        .STEP_NOT_ELIGIBLE,
                failure.code());
        assertEquals(REQUEST_PATH + ".stepId", failure.path());
        assertEquals(
                "Step is not eligible for activation",
                failure.getMessage());
        assertNull(failure.getCause());
        return failure;
    }

    private static void assertContractFailure(
            Runnable action,
            ViolationCode code,
            String path,
            String violationMessage,
            List<String> sentinels) {
        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                action::run);
        assertEquals(1, failure.violations().size());
        assertEquals(code, failure.primaryCode());
        var violation = failure.violations().get(0);
        assertEquals(code, violation.code());
        assertEquals(path, violation.path());
        assertEquals(violationMessage, violation.message());
        assertEquals(
                code + " at " + path + ": " + violationMessage,
                failure.getMessage());
        assertNull(failure.getCause());
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        for (String sentinel : sentinels) {
            assertFalse(failure.toString().contains(sentinel));
            assertFalse(stack.toString().contains(sentinel));
        }
    }

    private static CommittedStepActivationMaterializationRequest request(
            PersistedExecutionStartCommitted committed,
            PlanStepId stepId,
            StepActivationEventDraft draft,
            Instant checkpointCreatedAt) {
        return new CommittedStepActivationMaterializationRequest(
                committed,
                stepId,
                draft,
                checkpointCreatedAt);
    }

    private static CommittedStepActivationMaterializer materializer() {
        return new DeterministicCommittedStepActivationMaterializer();
    }

    private record PersistenceScenario(
            InMemoryPersistence persistence,
            PersistedExecutionStartCommitted committed,
            LeaseRecord lease,
            String leaseToken) {
    }

    private static void assertDraftFailure(
            EventId id,
            Instant occurredAt,
            EventType type,
            Optional<EventId> causationId,
            String correlationId,
            EventPayload payload,
            CommittedStepActivationMaterializationValidationCode code,
            String path) {
        assertFailure(
                () -> new StepActivationEventDraft(
                        id,
                        occurredAt,
                        type,
                        causationId,
                        correlationId,
                        payload),
                code,
                path);
    }

    private static void assertRequestFailure(
            PersistedExecutionStartCommitted committed,
            PlanStepId stepId,
            StepActivationEventDraft draft,
            Instant checkpointTime,
            String path) {
        assertFailure(
                () -> new CommittedStepActivationMaterializationRequest(
                        committed,
                        stepId,
                        draft,
                        checkpointTime),
                CommittedStepActivationMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                path);
    }

    private static void assertFailure(
            Runnable constructor,
            CommittedStepActivationMaterializationValidationCode code,
            String path) {
        var failure = assertThrows(
                CommittedStepActivationMaterializationValidationException.class,
                constructor::run);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("sentinel"));
    }
}
