package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ViolationCode;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicExecutionStartMaterializerTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-24T14:00:00Z");
    private static final String SOURCE_PATH =
            "executionStartMaterializationRequest.bootstrap"
                    + ".initialCheckpoint.checkpoint";

    @Test
    void materializesSnapshotDerivedEventAndStartedCheckpoint() {
        PersistedPlanBootstrap bootstrap = bootstrap("primary");
        EventId causeId = new EventId("event-cause-primary");
        InlineEventPayload payload =
                new InlineEventPayload(new ObjectValue(Map.of()));
        ExecutionStartEventDraft draft = eventDraft(
                new EventId("event-start-primary"),
                Optional.of(causeId),
                "correlation-primary",
                payload);

        MaterializedExecutionStart result =
                new DeterministicExecutionStartMaterializer().materialize(
                        request(
                                bootstrap,
                                draft,
                                CREATED_AT.plusSeconds(4)));

        assertEquals(draft.id(), result.startEvent().id());
        assertEquals(
                bootstrap.taskFrame().id(),
                result.startEvent().taskFrameId());
        assertEquals(bootstrap.plan().id(), result.startEvent().planId());
        assertEquals(1, result.startEvent().sequence());
        assertEquals(draft.occurredAt(), result.startEvent().occurredAt());
        assertSame(draft.type(), result.startEvent().type());
        assertEquals(Optional.of(causeId), result.startEvent().causationId());
        assertEquals(
                "correlation-primary",
                result.startEvent().correlationId());
        assertSame(payload, result.startEvent().payload());

        PlanRevision latest = bootstrap.plan().latestRevision();
        Checkpoint checkpoint = result.startedCheckpoint();
        assertEquals(bootstrap.taskFrame().id(), checkpoint.taskFrameId());
        assertEquals(bootstrap.plan().id(), checkpoint.planId());
        assertEquals(latest.id(), checkpoint.revisionId());
        assertEquals(latest.number(), checkpoint.revisionNumber());
        assertEquals(1, checkpoint.lastEventSequence());
        assertEquals(PlanExecutionState.ACTIVE, checkpoint.planState());
        assertEquals(
                Map.of(
                        latest.steps().get(0).id(),
                        StepExecutionState.NOT_STARTED,
                        latest.steps().get(1).id(),
                        StepExecutionState.NOT_STARTED),
                checkpoint.stepStates());
        assertEquals(
                Set.of(latest.steps().get(0).id()),
                latest.steps().get(1).dependencies());
        assertEquals(List.of(), checkpoint.receiptReferences());
        assertEquals(CREATED_AT.plusSeconds(4), checkpoint.createdAt());
        assertThrows(
                UnsupportedOperationException.class,
                () -> checkpoint.stepStates().put(
                        new PlanStepId("step-other"),
                        StepExecutionState.NOT_STARTED));
        assertThrows(
                UnsupportedOperationException.class,
                () -> checkpoint.receiptReferences().add(
                        new ReceiptId("receipt-other")));
    }

    @Test
    void requiredAndIdentifierFailuresUseStableCodeAndPath() {
        assertMaterializationFailure(
                () -> new DeterministicExecutionStartMaterializer()
                        .materialize(null),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartMaterializationRequest");
        assertMaterializationFailure(
                () -> new ExecutionStartMaterializationRequest(
                        null,
                        validEventDraft("request-bootstrap"),
                        CREATED_AT),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartMaterializationRequest.bootstrap");
        assertMaterializationFailure(
                () -> new ExecutionStartMaterializationRequest(
                        bootstrap("request-draft"),
                        null,
                        CREATED_AT),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartMaterializationRequest.eventDraft");
        assertMaterializationFailure(
                () -> new ExecutionStartMaterializationRequest(
                        bootstrap("request-time"),
                        validEventDraft("request-time"),
                        null),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartMaterializationRequest.checkpointCreatedAt");
        assertMaterializationFailure(
                () -> eventDraft(
                        null,
                        Optional.empty(),
                        "correlation-id",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartEventDraft.id");
        assertMaterializationFailure(
                () -> new ExecutionStartEventDraft(
                        new EventId("event-null-causation"),
                        CREATED_AT,
                        new EventType("execution-start"),
                        null,
                        "correlation-id",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartEventDraft.causationId");
        assertMaterializationFailure(
                () -> new ExecutionStartEventDraft(
                        new EventId("event-null-occurred-at"),
                        null,
                        new EventType("execution-start"),
                        Optional.empty(),
                        "correlation-id",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartEventDraft.occurredAt");
        assertMaterializationFailure(
                () -> new ExecutionStartEventDraft(
                        new EventId("event-null-type"),
                        CREATED_AT,
                        null,
                        Optional.empty(),
                        "correlation-id",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartEventDraft.type");
        assertMaterializationFailure(
                () -> new ExecutionStartEventDraft(
                        new EventId("event-null-correlation"),
                        CREATED_AT,
                        new EventType("execution-start"),
                        Optional.empty(),
                        null,
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartEventDraft.correlationId");
        assertMaterializationFailure(
                () -> new ExecutionStartEventDraft(
                        new EventId("event-null-payload"),
                        CREATED_AT,
                        new EventType("execution-start"),
                        Optional.empty(),
                        "correlation-id",
                        null),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "executionStartEventDraft.payload");
        assertMaterializationFailure(
                () -> eventDraft(
                        new EventId("event-blank-correlation"),
                        Optional.empty(),
                        " \t",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                ExecutionStartMaterializationValidationCode
                        .INVALID_IDENTIFIER,
                "executionStartEventDraft.correlationId");

        PersistedPlanBootstrap bootstrap = bootstrap("result-null");
        MaterializedExecutionStart valid = materializer().materialize(request(
                bootstrap,
                validEventDraft("result-null"),
                CREATED_AT.plusSeconds(4)));
        assertMaterializationFailure(
                () -> new MaterializedExecutionStart(
                        null,
                        valid.startedCheckpoint()),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "materializedExecutionStart.startEvent");
        assertMaterializationFailure(
                () -> new MaterializedExecutionStart(
                        valid.startEvent(),
                        null),
                ExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                "materializedExecutionStart.startedCheckpoint");
    }

    @Test
    void nonCanonicalSourceShapeFailsInFrozenOrderAndPaths() {
        assertNonCanonical(
                bootstrap(
                        "cursor",
                        1,
                        PlanExecutionState.ACTIVE,
                        StepExecutionState.ACTIVE,
                        List.of(new ReceiptId("receipt-cursor"))),
                SOURCE_PATH + ".lastEventSequence");
        assertNonCanonical(
                bootstrap(
                        "state",
                        0,
                        PlanExecutionState.ACTIVE,
                        StepExecutionState.ACTIVE,
                        List.of(new ReceiptId("receipt-state"))),
                SOURCE_PATH + ".planState");
        assertNonCanonical(
                bootstrap(
                        "steps",
                        0,
                        PlanExecutionState.NOT_STARTED,
                        StepExecutionState.ACTIVE,
                        List.of(new ReceiptId("receipt-steps"))),
                SOURCE_PATH + ".stepStates");
        assertNonCanonical(
                bootstrap(
                        "receipts",
                        0,
                        PlanExecutionState.NOT_STARTED,
                        StepExecutionState.NOT_STARTED,
                        List.of(new ReceiptId("receipt-source"))),
                SOURCE_PATH + ".receiptReferences");
    }

    @Test
    void sourceContractFailurePrecedesShapeAndEventAndPropagatesUnchanged() {
        PersistedPlanBootstrap canonical = bootstrap("source-contract");
        Checkpoint original = canonical.initialCheckpoint().checkpoint();
        Checkpoint mismatched = new Checkpoint(
                original.taskFrameId(),
                new PlanId("plan-mismatch"),
                original.revisionId(),
                original.revisionNumber(),
                1,
                PlanExecutionState.ACTIVE,
                original.stepStates(),
                List.of(),
                original.createdAt());
        PersistedPlanBootstrap forged = new PersistedPlanBootstrap(
                canonical.taskFrame(),
                canonical.plan(),
                new VersionedCheckpoint(1, mismatched));
        ExecutionStartEventDraft selfCausing =
                selfCausingEventDraft("source-contract");

        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        forged,
                        selfCausing,
                        CREATED_AT.plusSeconds(4))));

        assertEquals(
                ViolationCode.CHECKPOINT_PLAN_MISMATCH,
                failure.primaryCode());
        assertEquals(
                "checkpoint.planId",
                failure.violations().get(0).path());
    }

    @Test
    void sourceStateAndStepContractFailuresPrecedeCanonicalShapeGuard() {
        PersistedPlanBootstrap canonical = bootstrap("source-precedence");
        Checkpoint source = canonical.initialCheckpoint().checkpoint();
        PlanStepId expectedStep =
                canonical.plan().latestRevision().steps().get(0).id();
        PlanStepId dependentStep =
                canonical.plan().latestRevision().steps().get(1).id();

        assertSourceContractFailure(
                canonical,
                new Checkpoint(
                        source.taskFrameId(),
                        source.planId(),
                        source.revisionId(),
                        source.revisionNumber(),
                        0,
                        PlanExecutionState.SUCCEEDED,
                        source.stepStates(),
                        List.of(),
                        source.createdAt()),
                ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                "checkpoint.planState");
        assertSourceContractFailure(
                canonical,
                new Checkpoint(
                        source.taskFrameId(),
                        source.planId(),
                        source.revisionId(),
                        source.revisionNumber(),
                        0,
                        PlanExecutionState.NOT_STARTED,
                        Map.of(
                                expectedStep,
                                StepExecutionState.SUCCEEDED,
                                dependentStep,
                                StepExecutionState.NOT_STARTED),
                        List.of(),
                        source.createdAt()),
                ViolationCode.CHECKPOINT_COMPLETION_FACT_MISSING,
                "checkpoint.stepStates." + expectedStep.value());
        assertSourceContractFailure(
                canonical,
                new Checkpoint(
                        source.taskFrameId(),
                        source.planId(),
                        source.revisionId(),
                        source.revisionNumber(),
                        0,
                        PlanExecutionState.NOT_STARTED,
                        Map.of(
                                dependentStep,
                                StepExecutionState.NOT_STARTED),
                        List.of(),
                        source.createdAt()),
                ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                "checkpoint.stepStates." + expectedStep.value());

        PlanStepId unknownStep = new PlanStepId("step-source-unknown");
        assertSourceContractFailure(
                canonical,
                new Checkpoint(
                        source.taskFrameId(),
                        source.planId(),
                        source.revisionId(),
                        source.revisionNumber(),
                        0,
                        PlanExecutionState.NOT_STARTED,
                        Map.of(
                                expectedStep,
                                StepExecutionState.NOT_STARTED,
                                dependentStep,
                                StepExecutionState.NOT_STARTED,
                                unknownStep,
                                StepExecutionState.NOT_STARTED),
                        List.of(),
                        source.createdAt()),
                ViolationCode.CHECKPOINT_UNKNOWN_STEP,
                "checkpoint.stepStates." + unknownStep.value());
    }

    @Test
    void sourceShapeFailurePrecedesEventFailure() {
        PersistedPlanBootstrap forged = bootstrap(
                "shape-before-event",
                1,
                PlanExecutionState.NOT_STARTED,
                StepExecutionState.NOT_STARTED,
                List.of());

        ExecutionStartMaterializationValidationException failure =
                assertThrows(
                        ExecutionStartMaterializationValidationException.class,
                        () -> materializer().materialize(request(
                                forged,
                                selfCausingEventDraft("shape-before-event"),
                                CREATED_AT.plusSeconds(4))));

        assertEquals(
                ExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_BOOTSTRAP,
                failure.code());
        assertEquals(SOURCE_PATH + ".lastEventSequence", failure.path());
    }

    @Test
    void eventFailurePrecedesTargetCheckpointFailureAndPropagatesUnchanged() {
        PersistedPlanBootstrap bootstrap = bootstrap("event-before-target");

        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        bootstrap,
                        selfCausingEventDraft("event-before-target"),
                        CREATED_AT.minusSeconds(1))));

        assertEquals(ViolationCode.INCONSISTENT_REFERENCE, failure.primaryCode());
        assertEquals(
                "event.causationId",
                failure.violations().get(0).path());
    }

    @Test
    void targetTimeRegressionAndInvalidCorrelationPreserveContractFailures() {
        PersistedPlanBootstrap bootstrap = bootstrap("contract-target");

        ContractViolationException timeFailure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        bootstrap,
                        validEventDraft("time-regression"),
                        CREATED_AT)));
        assertEquals(
                ViolationCode.CHECKPOINT_TIME_REGRESSION,
                timeFailure.primaryCode());
        assertEquals(
                "checkpoint.createdAt",
                timeFailure.violations().get(0).path());

        ExecutionStartEventDraft invalidCorrelation = eventDraft(
                new EventId("event-invalid-correlation"),
                Optional.empty(),
                "contains whitespace",
                new InlineEventPayload(new ObjectValue(Map.of())));
        ContractViolationException correlationFailure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        bootstrap,
                        invalidCorrelation,
                        CREATED_AT.plusSeconds(4))));
        assertEquals(ViolationCode.INVALID_ID, correlationFailure.primaryCode());
        assertEquals(
                "event.correlationId",
                correlationFailure.violations().get(0).path());
    }

    @Test
    void completionFactsFailAsSourceContractBeforeAllNotStartedTargetExists() {
        PersistedPlanBootstrap original = bootstrap("facts");
        PlanRevision initial = original.plan().latestRevision();
        PlanRevision withFact = new PlanRevision(
                initial.id(),
                initial.taskFrameId(),
                initial.number(),
                initial.parentRevisionId(),
                initial.reason(),
                initial.createdAt(),
                initial.steps(),
                Map.of(
                        initial.steps().get(0).id(),
                        new CompletionFact(
                                initial.steps().get(0).id(),
                                "outcome-hash",
                                CREATED_AT.plusSeconds(2),
                                List.of())));
        Plan planWithFact = new Plan(
                original.plan().id(),
                original.taskFrame().id(),
                List.of(withFact));
        PersistedPlanBootstrap forged = new PersistedPlanBootstrap(
                original.taskFrame(),
                planWithFact,
                original.initialCheckpoint());

        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        forged,
                        validEventDraft("facts"),
                        CREATED_AT.plusSeconds(4))));

        assertEquals(
                ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                failure.primaryCode());
        assertEquals(
                "checkpoint.stepStates."
                        + initial.steps().get(0).id().value(),
                failure.violations().get(0).path());
    }

    @Test
    void snapshotCandidateDoesNotObserveAnIndependentCurrentRevision() {
        PersistedPlanBootstrap snapshot = bootstrap("stale-snapshot");
        PlanRevision revisionOne = snapshot.plan().latestRevision();
        PlanRevision revisionTwo = new PlanRevision(
                new PlanRevisionId("revision-materialization-current-r2"),
                snapshot.taskFrame().id(),
                2,
                Optional.of(revisionOne.id()),
                "concurrent append",
                CREATED_AT.plusSeconds(3),
                revisionOne.steps(),
                Map.of());
        Plan independentCurrentPlan = new Plan(
                snapshot.plan().id(),
                snapshot.taskFrame().id(),
                List.of(revisionOne, revisionTwo));

        MaterializedExecutionStart result = materializer().materialize(request(
                snapshot,
                validEventDraft("stale-snapshot"),
                CREATED_AT.plusSeconds(4)));

        assertEquals(
                revisionTwo.id(),
                independentCurrentPlan.latestRevision().id());
        assertEquals(revisionOne.id(), result.startedCheckpoint().revisionId());
        assertEquals(1, result.startedCheckpoint().revisionNumber());
        assertEquals(snapshot.plan().id(), result.startEvent().planId());
    }

    @Test
    void repeatedCrossInstanceConcurrentAndInterleavedCallsAreDeterministic()
            throws Exception {
        ExecutionStartMaterializationRequest requestA = request(
                bootstrap("deterministic-a"),
                validEventDraft("deterministic-a"),
                CREATED_AT.plusSeconds(4));
        ExecutionStartMaterializationRequest requestB = request(
                bootstrap("deterministic-b"),
                validEventDraft("deterministic-b"),
                CREATED_AT.plusSeconds(5));
        ExecutionStartEventDraft draftSnapshot = new ExecutionStartEventDraft(
                requestA.eventDraft().id(),
                requestA.eventDraft().occurredAt(),
                requestA.eventDraft().type(),
                requestA.eventDraft().causationId(),
                requestA.eventDraft().correlationId(),
                requestA.eventDraft().payload());
        PersistedPlanBootstrap bootstrapSnapshot =
                new PersistedPlanBootstrap(
                        requestA.bootstrap().taskFrame(),
                        requestA.bootstrap().plan(),
                        new VersionedCheckpoint(
                                requestA.bootstrap().initialCheckpoint()
                                        .version(),
                                requestA.bootstrap().initialCheckpoint()
                                        .checkpoint()));
        ExecutionStartMaterializationRequest requestSnapshot =
                new ExecutionStartMaterializationRequest(
                        bootstrapSnapshot,
                        draftSnapshot,
                        requestA.checkpointCreatedAt());
        ExecutionStartMaterializer first = materializer();
        ExecutionStartMaterializer second = materializer();

        MaterializedExecutionStart expectedA = first.materialize(requestA);
        MaterializedExecutionStart expectedB = first.materialize(requestB);
        assertEquals(expectedA, first.materialize(requestA));
        assertEquals(expectedA, second.materialize(requestA));
        assertEquals(expectedB, second.materialize(requestB));
        assertNotSame(
                expectedA.startedCheckpoint().stepStates(),
                requestA.bootstrap().initialCheckpoint().checkpoint()
                        .stepStates());

        List<Callable<MaterializedExecutionStart>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(() -> first.materialize(requestA));
        }
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            for (var future : executor.invokeAll(
                    calls,
                    5,
                    TimeUnit.SECONDS)) {
                assertFalse(future.isCancelled());
                assertEquals(expectedA, future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(requestSnapshot, requestA);
        assertEquals(bootstrapSnapshot, requestA.bootstrap());
        assertEquals(draftSnapshot, requestA.eventDraft());
    }

    private static void assertNonCanonical(
            PersistedPlanBootstrap bootstrap,
            String path) {
        assertMaterializationFailure(
                () -> materializer().materialize(request(
                        bootstrap,
                        validEventDraft("non-canonical"),
                        CREATED_AT.plusSeconds(4))),
                ExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_BOOTSTRAP,
                path);
    }

    private static void assertSourceContractFailure(
            PersistedPlanBootstrap canonical,
            Checkpoint source,
            ViolationCode code,
            String path) {
        PersistedPlanBootstrap forged = new PersistedPlanBootstrap(
                canonical.taskFrame(),
                canonical.plan(),
                new VersionedCheckpoint(1, source));
        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        forged,
                        validEventDraft("source-contract-precedence"),
                        CREATED_AT.plusSeconds(4))));
        assertEquals(code, failure.primaryCode());
        assertEquals(path, failure.violations().get(0).path());
    }

    private static void assertMaterializationFailure(
            Runnable action,
            ExecutionStartMaterializationValidationCode code,
            String path) {
        ExecutionStartMaterializationValidationException failure =
                assertThrows(
                        ExecutionStartMaterializationValidationException.class,
                        action::run);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }

    private static ExecutionStartMaterializer materializer() {
        return new DeterministicExecutionStartMaterializer();
    }

    private static ExecutionStartMaterializationRequest request(
            PersistedPlanBootstrap bootstrap,
            ExecutionStartEventDraft draft,
            Instant checkpointCreatedAt) {
        return new ExecutionStartMaterializationRequest(
                bootstrap,
                draft,
                checkpointCreatedAt);
    }

    private static ExecutionStartEventDraft validEventDraft(String suffix) {
        return eventDraft(
                new EventId("event-start-" + suffix),
                Optional.empty(),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    private static ExecutionStartEventDraft selfCausingEventDraft(
            String suffix) {
        EventId id = new EventId("event-self-" + suffix);
        return eventDraft(
                id,
                Optional.of(id),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    private static ExecutionStartEventDraft eventDraft(
            EventId id,
            Optional<EventId> causationId,
            String correlationId,
            InlineEventPayload payload) {
        return new ExecutionStartEventDraft(
                id,
                CREATED_AT.plusSeconds(3),
                new EventType("execution-start"),
                causationId,
                correlationId,
                payload);
    }

    private static PersistedPlanBootstrap bootstrap(String suffix) {
        return bootstrap(
                suffix,
                0,
                PlanExecutionState.NOT_STARTED,
                StepExecutionState.NOT_STARTED,
                List.of());
    }

    private static PersistedPlanBootstrap bootstrap(
            String suffix,
            long sequence,
            PlanExecutionState planState,
            StepExecutionState stepState,
            List<ReceiptId> receipts) {
        TaskFrameId taskFrameId =
                new TaskFrameId("task-materialization-" + suffix);
        PlanId planId = new PlanId("plan-materialization-" + suffix);
        PlanStepId stepId = new PlanStepId("step-materialization-" + suffix);
        PlanStepId dependentStepId =
                new PlanStepId("step-materialization-dependent-" + suffix);
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "Prepare " + suffix,
                List.of("paper"),
                List.of("workspace diff"),
                List.of(),
                Optional.empty(),
                executionProfile(),
                CREATED_AT);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-materialization-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                CREATED_AT.plusSeconds(1),
                List.of(
                        new PlanStep(
                                stepId,
                                "execute " + suffix,
                                "produce " + suffix,
                                Set.of(),
                                List.of("result exists"),
                                new BoundedExecutionHints(
                                        2,
                                        Duration.ofMinutes(2))),
                        new PlanStep(
                                dependentStepId,
                                "verify " + suffix,
                                "verify result " + suffix,
                                Set.of(stepId),
                                List.of("verification exists"),
                                new BoundedExecutionHints(
                                        2,
                                        Duration.ofMinutes(2)))),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Checkpoint source = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                sequence,
                planState,
                Map.of(
                        stepId,
                        stepState,
                        dependentStepId,
                        stepState),
                receipts,
                CREATED_AT.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, source));
    }

    private static ExecutionProfile executionProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        1024,
                        1024,
                        1),
                Set.of());
    }
}
