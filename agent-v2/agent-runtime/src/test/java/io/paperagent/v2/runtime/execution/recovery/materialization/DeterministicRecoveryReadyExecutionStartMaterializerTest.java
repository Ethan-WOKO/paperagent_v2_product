package io.paperagent.v2.runtime.execution.recovery.materialization;

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
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.ExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicRecoveryReadyExecutionStartMaterializerTest {
    private static final Instant BASE_TIME =
            Instant.parse("2026-07-24T14:00:00Z");
    private static final String REQUEST_PATH =
            "recoveryReadyExecutionStartMaterializationRequest";
    private static final String SOURCE_PATH =
            REQUEST_PATH
                    + ".ready.bootstrap.initialCheckpoint.checkpoint";

    @Test
    void canonicalReadyMatchesFreshMaterializerWithoutProductionDelegation() {
        PersistedPlanBootstrap bootstrap = bootstrap("equal");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(bootstrap, bootstrap.plan());
        ExecutionStartEventDraft draft = draft("equal");
        Instant checkpointTime = BASE_TIME.plusSeconds(4);

        MaterializedExecutionStart recovery = materializer().materialize(
                request(ready, draft, checkpointTime));
        MaterializedExecutionStart fresh =
                new DeterministicExecutionStartMaterializer().materialize(
                        new ExecutionStartMaterializationRequest(
                                bootstrap,
                                draft,
                                checkpointTime));

        assertEquals(fresh, recovery);
        assertEquals(1, recovery.startEvent().sequence());
        assertEquals(PlanExecutionState.ACTIVE,
                recovery.startedCheckpoint().planState());
        assertEquals(List.of(),
                recovery.startedCheckpoint().receiptReferences());
    }

    @Test
    void revisedReadyBindsCurrentLatestAndUsesOnlyItsSteps() {
        PersistedPlanBootstrap bootstrap = bootstrap("revised");
        Plan currentPlan = revisedPlan(bootstrap, "revised", true, false);
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(bootstrap, currentPlan);
        ExecutionStartEventDraft draft = draft("revised");

        MaterializedExecutionStart result = materializer().materialize(
                request(ready, draft, BASE_TIME.plusSeconds(4)));

        PlanRevision latest = currentPlan.latestRevision();
        Set<PlanStepId> expectedStepIds = latest.steps().stream()
                .map(PlanStep::id)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(draft.id(), result.startEvent().id());
        assertEquals(draft.occurredAt(), result.startEvent().occurredAt());
        assertEquals(draft.type(), result.startEvent().type());
        assertEquals(draft.causationId(), result.startEvent().causationId());
        assertEquals(draft.correlationId(),
                result.startEvent().correlationId());
        assertEquals(draft.payload(), result.startEvent().payload());
        assertEquals(bootstrap.taskFrame().id(),
                result.startEvent().taskFrameId());
        assertEquals(currentPlan.id(), result.startEvent().planId());
        assertEquals(1, result.startEvent().sequence());

        Checkpoint checkpoint = result.startedCheckpoint();
        assertEquals(currentPlan.id(), checkpoint.planId());
        assertEquals(bootstrap.taskFrame().id(), checkpoint.taskFrameId());
        assertEquals(latest.id(), checkpoint.revisionId());
        assertEquals(2, checkpoint.revisionNumber());
        assertEquals(expectedStepIds, checkpoint.stepStates().keySet());
        assertTrue(checkpoint.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.NOT_STARTED));
        assertFalse(checkpoint.stepStates().keySet().stream()
                .anyMatch(id -> id.value().contains("revision-one")));
        assertEquals(1, checkpoint.lastEventSequence());
        assertEquals(BASE_TIME.plusSeconds(4), checkpoint.createdAt());
    }

    @Test
    void draftAndTimeArePreservedAndInputsAndResultsRemainImmutable() {
        PersistedPlanBootstrap bootstrap = bootstrap("immutable");
        Plan currentPlan = revisedPlan(bootstrap, "immutable", true, false);
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(bootstrap, currentPlan);
        EventId cause = new EventId("cause-immutable");
        InlineEventPayload payload = new InlineEventPayload(
                new ObjectValue(Map.of("detail",
                        new io.paperagent.v2.contracts.TextValue(
                                "payload-immutable"))));
        ExecutionStartEventDraft draft = new ExecutionStartEventDraft(
                new EventId("event-immutable"),
                BASE_TIME.plusSeconds(100),
                new EventType("custom-start"),
                Optional.of(cause),
                "correlation-immutable",
                payload);
        var request = request(ready, draft, BASE_TIME.plusSeconds(5));
        var requestCopy = request(ready, draft, BASE_TIME.plusSeconds(5));

        MaterializedExecutionStart result = materializer().materialize(request);

        assertEquals(requestCopy, request);
        assertEquals(payload, result.startEvent().payload());
        assertEquals(BASE_TIME.plusSeconds(100),
                result.startEvent().occurredAt());
        assertEquals(BASE_TIME.plusSeconds(5),
                result.startedCheckpoint().createdAt());
        assertNotSame(
                bootstrap.initialCheckpoint().checkpoint().stepStates(),
                result.startedCheckpoint().stepStates());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.startedCheckpoint().stepStates().put(
                        new PlanStepId("step-forbidden"),
                        StepExecutionState.NOT_STARTED));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.startedCheckpoint().receiptReferences().add(
                        new ReceiptId("receipt-forbidden")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ready.currentPlan().revisions().clear());
    }

    @Test
    void requiredValuesUseStableCodeAndPathAndRequestIsOpaque() {
        PersistedPlanBootstrap bootstrap = bootstrap("required");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(bootstrap, bootstrap.plan());
        ExecutionStartEventDraft draft = draft("required");

        assertFailure(
                () -> new RecoveryReadyExecutionStartMaterializationRequest(
                        null,
                        draft,
                        BASE_TIME),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                REQUEST_PATH + ".ready");
        assertFailure(
                () -> new RecoveryReadyExecutionStartMaterializationRequest(
                        ready,
                        null,
                        BASE_TIME),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                REQUEST_PATH + ".eventDraft");
        assertFailure(
                () -> new RecoveryReadyExecutionStartMaterializationRequest(
                        ready,
                        draft,
                        null),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                REQUEST_PATH + ".checkpointCreatedAt");
        assertFailure(
                () -> materializer().materialize(null),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                REQUEST_PATH);

        String sensitive = "sensitive-ready-payload";
        PersistedPlanBootstrap sensitiveBootstrap = bootstrap(sensitive);
        PersistedExecutionStartReady sensitiveReady =
                new PersistedExecutionStartReady(
                        sensitiveBootstrap,
                        sensitiveBootstrap.plan());
        ExecutionStartEventDraft sensitiveDraft = new ExecutionStartEventDraft(
                new EventId("event-" + sensitive),
                BASE_TIME.plusSeconds(3),
                new EventType("start"),
                Optional.empty(),
                "correlation-" + sensitive,
                new InlineEventPayload(new ObjectValue(Map.of(
                        "secret",
                        new io.paperagent.v2.contracts.TextValue(sensitive)))));
        var sensitiveRequest = request(
                sensitiveReady,
                sensitiveDraft,
                Instant.parse("2035-06-07T08:09:10Z"));

        assertEquals(
                "RecoveryReadyExecutionStartMaterializationRequest"
                        + "[ready=<provided>, eventDraft=<provided>, "
                        + "checkpointCreatedAt=<provided>]",
                sensitiveRequest.toString());
        assertFalse(sensitiveRequest.toString().contains(sensitive));
        assertFalse(sensitiveRequest.toString().contains("2035-06-07"));

        PersistedPlanBootstrap nonCanonical =
                bootstrapWithSource(
                        sensitive,
                        1,
                        PlanExecutionState.NOT_STARTED,
                        StepExecutionState.NOT_STARTED,
                        List.of());
        var failure = assertThrows(
                RecoveryReadyExecutionStartMaterializationValidationException
                        .class,
                () -> materializer().materialize(request(
                        new PersistedExecutionStartReady(
                                nonCanonical,
                                nonCanonical.plan()),
                        sensitiveDraft,
                        BASE_TIME.plusSeconds(4))));
        assertFalse(failure.getMessage().contains(sensitive));
        assertFalse(failure.toString().contains(sensitive));
    }

    @Test
    void canonicalSourceGuardsUseFrozenAggregateOrderAndPaths() {
        assertNonCanonical(
                bootstrapWithSource(
                        "cursor",
                        1,
                        PlanExecutionState.ACTIVE,
                        StepExecutionState.ACTIVE,
                        List.of(new ReceiptId("receipt-cursor"))),
                SOURCE_PATH + ".lastEventSequence");
        assertNonCanonical(
                bootstrapWithSource(
                        "state",
                        0,
                        PlanExecutionState.ACTIVE,
                        StepExecutionState.ACTIVE,
                        List.of(new ReceiptId("receipt-state"))),
                SOURCE_PATH + ".planState");
        assertNonCanonical(
                bootstrapWithSource(
                        "steps",
                        0,
                        PlanExecutionState.NOT_STARTED,
                        StepExecutionState.ACTIVE,
                        List.of(new ReceiptId("receipt-steps"))),
                SOURCE_PATH + ".stepStates");
        assertNonCanonical(
                bootstrapWithSource(
                        "receipts",
                        0,
                        PlanExecutionState.NOT_STARTED,
                        StepExecutionState.NOT_STARTED,
                        List.of(new ReceiptId("receipt-source"))),
                SOURCE_PATH + ".receiptReferences");
    }

    @Test
    void sourceContractsPrecedeSnapshotShapeAndLaterCandidateFailures() {
        PersistedPlanBootstrap forged = bootstrapWithSourceContractFailure(
                "source-contract");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(forged, forged.plan());

        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        ready,
                        selfCausingDraft("source-contract"),
                        BASE_TIME)));

        assertEquals(
                ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                failure.primaryCode());
        assertTrue(failure.violations().get(0).path()
                .startsWith("checkpoint.stepStates."));
    }

    @Test
    void currentFactsPrecedeEventAndTargetAndUseFrozenPath() {
        PersistedPlanBootstrap bootstrap = bootstrap("facts");
        Plan currentPlan = revisedPlan(bootstrap, "facts", false, true);
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(bootstrap, currentPlan);

        assertFailure(
                () -> materializer().materialize(request(
                        ready,
                        selfCausingDraft("facts"),
                        BASE_TIME)),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_READY_SNAPSHOT,
                REQUEST_PATH
                        + ".ready.currentPlan.latestRevision.completedFacts");

        PersistedPlanBootstrap sourceFirst = bootstrapWithSource(
                "source-before-facts",
                1,
                PlanExecutionState.ACTIVE,
                StepExecutionState.ACTIVE,
                List.of(new ReceiptId("receipt-source-before-facts")));
        Plan factsAfterSource =
                revisedPlan(sourceFirst, "source-before-facts", false, true);
        assertFailure(
                () -> materializer().materialize(request(
                        new PersistedExecutionStartReady(
                                sourceFirst,
                                factsAfterSource),
                        selfCausingDraft("source-before-facts"),
                        BASE_TIME)),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_READY_SNAPSHOT,
                SOURCE_PATH + ".lastEventSequence");
    }

    @Test
    void eventAndTargetContractFailuresPropagateUnchanged() {
        PersistedPlanBootstrap bootstrap = bootstrap("contracts");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(bootstrap, bootstrap.plan());

        ContractViolationException eventFailure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        ready,
                        selfCausingDraft("contracts"),
                        BASE_TIME)));
        assertEquals(
                ViolationCode.INCONSISTENT_REFERENCE,
                eventFailure.primaryCode());
        assertEquals(
                "event.causationId",
                eventFailure.violations().get(0).path());

        ContractViolationException timeFailure = assertThrows(
                ContractViolationException.class,
                () -> materializer().materialize(request(
                        ready,
                        draft("contracts-time"),
                        BASE_TIME)));
        assertEquals(
                ViolationCode.CHECKPOINT_TIME_REGRESSION,
                timeFailure.primaryCode());
        assertEquals(
                "checkpoint.createdAt",
                timeFailure.violations().get(0).path());
    }

    @Test
    void repeatedCrossInstanceConcurrentAndInterleavedCallsAreDeterministic()
            throws Exception {
        PersistedPlanBootstrap bootstrapA = bootstrap("deterministic-a");
        PersistedPlanBootstrap bootstrapB = bootstrap("deterministic-b");
        var requestA = request(
                new PersistedExecutionStartReady(
                        bootstrapA,
                        revisedPlan(
                                bootstrapA,
                                "deterministic-a",
                                true,
                                false)),
                draft("deterministic-a"),
                BASE_TIME.plusSeconds(4));
        var requestB = request(
                new PersistedExecutionStartReady(
                        bootstrapB,
                        revisedPlan(
                                bootstrapB,
                                "deterministic-b",
                                true,
                                false)),
                draft("deterministic-b"),
                BASE_TIME.plusSeconds(5));
        RecoveryReadyExecutionStartMaterializer first = materializer();
        RecoveryReadyExecutionStartMaterializer second = materializer();
        MaterializedExecutionStart expectedA = first.materialize(requestA);
        MaterializedExecutionStart expectedB = first.materialize(requestB);

        assertEquals(expectedA, first.materialize(requestA));
        assertEquals(expectedA, second.materialize(requestA));
        assertEquals(expectedB, second.materialize(requestB));
        List<Callable<MaterializedExecutionStart>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            var selected = index % 2 == 0 ? requestA : requestB;
            calls.add(() -> first.materialize(selected));
        }

        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            var futures = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            for (int index = 0; index < futures.size(); index++) {
                var future = futures.get(index);
                assertFalse(future.isCancelled());
                assertEquals(
                        index % 2 == 0 ? expectedA : expectedB,
                        future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertNonCanonical(
            PersistedPlanBootstrap bootstrap,
            String path) {
        assertFailure(
                () -> materializer().materialize(request(
                        new PersistedExecutionStartReady(
                                bootstrap,
                                bootstrap.plan()),
                        draft("non-canonical"),
                        BASE_TIME.plusSeconds(4))),
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_READY_SNAPSHOT,
                path);
    }

    private static void assertFailure(
            Runnable action,
            RecoveryReadyExecutionStartMaterializationValidationCode code,
            String path) {
        var failure = assertThrows(
                RecoveryReadyExecutionStartMaterializationValidationException
                        .class,
                action::run);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }

    private static RecoveryReadyExecutionStartMaterializer materializer() {
        return new DeterministicRecoveryReadyExecutionStartMaterializer();
    }

    private static RecoveryReadyExecutionStartMaterializationRequest request(
            PersistedExecutionStartReady ready,
            ExecutionStartEventDraft draft,
            Instant checkpointCreatedAt) {
        return new RecoveryReadyExecutionStartMaterializationRequest(
                ready,
                draft,
                checkpointCreatedAt);
    }

    private static ExecutionStartEventDraft draft(String suffix) {
        return new ExecutionStartEventDraft(
                new EventId("event-" + suffix),
                BASE_TIME.plusSeconds(3),
                new EventType("execution-start"),
                Optional.empty(),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    private static ExecutionStartEventDraft selfCausingDraft(String suffix) {
        EventId id = new EventId("event-self-" + suffix);
        return new ExecutionStartEventDraft(
                id,
                BASE_TIME.plusSeconds(3),
                new EventType("execution-start"),
                Optional.of(id),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    private static PersistedPlanBootstrap bootstrap(String suffix) {
        return bootstrapWithSource(
                suffix,
                0,
                PlanExecutionState.NOT_STARTED,
                StepExecutionState.NOT_STARTED,
                List.of());
    }

    private static PersistedPlanBootstrap bootstrapWithSource(
            String suffix,
            long sequence,
            PlanExecutionState planState,
            StepExecutionState stepState,
            List<ReceiptId> receipts) {
        TaskFrame taskFrame = taskFrame(suffix);
        PlanRevision revision = revisionOne(
                taskFrame.id(),
                suffix,
                Map.of());
        Plan plan = new Plan(
                new PlanId("plan-" + suffix),
                taskFrame.id(),
                List.of(revision));
        Checkpoint source = new Checkpoint(
                taskFrame.id(),
                plan.id(),
                revision.id(),
                revision.number(),
                sequence,
                planState,
                Map.of(
                        revision.steps().get(0).id(),
                        stepState,
                        revision.steps().get(1).id(),
                        stepState),
                receipts,
                BASE_TIME.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, source));
    }

    private static PersistedPlanBootstrap bootstrapWithSourceContractFailure(
            String suffix) {
        TaskFrame taskFrame = taskFrame(suffix);
        PlanRevision base = revisionOne(taskFrame.id(), suffix, Map.of());
        PlanStep firstStep = base.steps().get(0);
        CompletionFact fact = new CompletionFact(
                firstStep.id(),
                "hash-" + suffix,
                BASE_TIME.plusSeconds(2),
                List.of());
        PlanRevision revision = revisionOne(
                taskFrame.id(),
                suffix,
                Map.of(firstStep.id(), fact));
        Plan plan = new Plan(
                new PlanId("plan-" + suffix),
                taskFrame.id(),
                List.of(revision));
        Checkpoint source = new Checkpoint(
                taskFrame.id(),
                plan.id(),
                revision.id(),
                1,
                1,
                PlanExecutionState.ACTIVE,
                Map.of(
                        revision.steps().get(0).id(),
                        StepExecutionState.NOT_STARTED,
                        revision.steps().get(1).id(),
                        StepExecutionState.NOT_STARTED),
                List.of(),
                BASE_TIME.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, source));
    }

    private static Plan revisedPlan(
            PersistedPlanBootstrap bootstrap,
            String suffix,
            boolean replaceSteps,
            boolean addCompletionFact) {
        PlanRevision previous = bootstrap.plan().latestRevision();
        List<PlanStep> steps = replaceSteps
                ? List.of(
                        step("step-revision-two-first-" + suffix, Set.of()),
                        step(
                                "step-revision-two-second-" + suffix,
                                Set.of(new PlanStepId(
                                        "step-revision-two-first-" + suffix))))
                : previous.steps();
        Map<PlanStepId, CompletionFact> facts = Map.of();
        if (addCompletionFact) {
            PlanStep completed = previous.steps().get(0);
            facts = Map.of(
                    completed.id(),
                    new CompletionFact(
                            completed.id(),
                            "hash-" + suffix,
                            BASE_TIME.plusSeconds(3),
                            List.of()));
        }
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-two-" + suffix),
                bootstrap.taskFrame().id(),
                2,
                Optional.of(previous.id()),
                "pre-start refinement " + suffix,
                BASE_TIME.plusSeconds(3),
                steps,
                facts);
        return new Plan(
                bootstrap.plan().id(),
                bootstrap.taskFrame().id(),
                List.of(previous, revision));
    }

    private static PlanRevision revisionOne(
            TaskFrameId taskFrameId,
            String suffix,
            Map<PlanStepId, CompletionFact> facts) {
        PlanStep first =
                step("step-revision-one-first-" + suffix, Set.of());
        PlanStep second = step(
                "step-revision-one-second-" + suffix,
                Set.of(first.id()));
        return new PlanRevision(
                new PlanRevisionId("revision-one-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                BASE_TIME.plusSeconds(1),
                List.of(first, second),
                facts);
    }

    private static PlanStep step(
            String id,
            Set<PlanStepId> dependencies) {
        return new PlanStep(
                new PlanStepId(id),
                "execute " + id,
                "produce " + id,
                dependencies,
                List.of("result exists"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
    }

    private static TaskFrame taskFrame(String suffix) {
        return new TaskFrame(
                new TaskFrameId("task-" + suffix),
                "Prepare " + suffix,
                List.of("paper"),
                List.of("workspace diff"),
                List.of(),
                Optional.empty(),
                executionProfile(),
                BASE_TIME);
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
