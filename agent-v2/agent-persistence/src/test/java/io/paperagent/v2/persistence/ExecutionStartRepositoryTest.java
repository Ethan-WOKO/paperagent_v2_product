package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRepositoryTest {
    private static final String OWNER = "worker-a";
    private static final String TOKEN = "lease-token-a";

    @Test
    void recordsValidateOnlyTheirFrozenStructuralShape() {
        Plan plan = PersistenceFixtures.plan();
        EventEnvelope event = PersistenceFixtures.event("start-structural", 1);
        Checkpoint checkpoint = PersistenceFixtures.startedCheckpoint(plan);

        assertNullMessage(
                () -> new ExecutionStartRequest(null, TOKEN, 1, event, checkpoint),
                "planId");
        assertNullMessage(
                () -> new ExecutionStartRequest(plan.id(), null, 1, event, checkpoint),
                "leaseToken");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionStartRequest(plan.id(), " ", 1, event, checkpoint));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionStartRequest(plan.id(), TOKEN, 0, event, checkpoint));
        assertNullMessage(
                () -> new ExecutionStartRequest(plan.id(), TOKEN, 1, null, checkpoint),
                "startEvent");
        assertNullMessage(
                () -> new ExecutionStartRequest(plan.id(), TOKEN, 1, event, null),
                "startedCheckpoint");

        VersionedCheckpoint versionTwo = new VersionedCheckpoint(2, checkpoint);
        assertNullMessage(
                () -> new PersistedExecutionStart(null, OWNER, 1, event, versionTwo),
                "planId");
        assertNullMessage(
                () -> new PersistedExecutionStart(plan.id(), null, 1, event, versionTwo),
                "leaseOwnerId");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedExecutionStart(plan.id(), " ", 1, event, versionTwo));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedExecutionStart(plan.id(), OWNER, 0, event, versionTwo));
        assertNullMessage(
                () -> new PersistedExecutionStart(plan.id(), OWNER, 1, null, versionTwo),
                "startEvent");
        assertNullMessage(
                () -> new PersistedExecutionStart(plan.id(), OWNER, 1, event, null),
                "startedCheckpoint");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedExecutionStart(
                        plan.id(),
                        OWNER,
                        1,
                        event,
                        new VersionedCheckpoint(1, checkpoint)));
    }

    @Test
    void firstStartAtomicallyPersistsEventCheckpointAndMarker() {
        Scenario scenario = liveScenario("start-applied");

        PersistenceResult<PersistedExecutionStart> result =
                scenario.persistence().executionStarts().start(scenario.request());

        assertEquals(PersistenceOutcome.APPLIED, result.outcome());
        PersistedExecutionStart persisted = result.value().orElseThrow();
        assertEquals(scenario.plan().id(), persisted.planId());
        assertEquals(OWNER, persisted.leaseOwnerId());
        assertEquals(1, persisted.fencingToken());
        assertEquals(scenario.request().startEvent(), persisted.startEvent());
        assertEquals(
                new VersionedCheckpoint(2, scenario.request().startedCheckpoint()),
                persisted.startedCheckpoint());
        assertEquals(
                List.of(scenario.request().startEvent()),
                scenario.persistence().events()
                        .readAfter(scenario.plan().id(), 0)
                        .value().orElseThrow());
        assertEquals(
                persisted.startedCheckpoint(),
                scenario.persistence().checkpoints()
                        .find(scenario.plan().id())
                        .value().orElseThrow());
        assertEquals(2, scenario.clock().observationCount());

        assertEquals(
                PersistenceOutcome.REPLAYED,
                scenario.persistence().executionStarts().start(scenario.request()).outcome());
    }

    @Test
    void permanentMarkerUsesCompleteIdentityAndNeverReadsClock() {
        Scenario scenario = liveScenario("start-marker");
        PersistedExecutionStart original = scenario.persistence().executionStarts()
                .start(scenario.request())
                .value().orElseThrow();
        int observations = scenario.clock().observationCount();
        scenario.clock().failOnObservation();

        PersistenceResult<PersistedExecutionStart> replay =
                scenario.persistence().executionStarts().start(scenario.request());
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(original, replay.value().orElseThrow());

        EventEnvelope event = scenario.request().startEvent();
        Checkpoint checkpoint = scenario.request().startedCheckpoint();
        List<ExecutionStartRequest> conflicts = List.of(
                new ExecutionStartRequest(
                        scenario.plan().id(), "other-token", 1, event, checkpoint),
                new ExecutionStartRequest(
                        scenario.plan().id(), TOKEN, 2, event, checkpoint),
                requestWithEvent(scenario.request(), event(
                        event, new EventId("different-id"), event.taskFrameId(),
                        event.planId(), event.sequence(), event.occurredAt(), event.type(),
                        event.causationId(), event.correlationId(), event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), new TaskFrameId("different-task"),
                        event.planId(), event.sequence(), event.occurredAt(), event.type(),
                        event.causationId(), event.correlationId(), event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(),
                        new PlanId("different-plan"), event.sequence(), event.occurredAt(),
                        event.type(), event.causationId(), event.correlationId(), event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(), event.planId(), 2,
                        event.occurredAt(), event.type(), event.causationId(),
                        event.correlationId(), event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(), event.planId(),
                        event.sequence(), event.occurredAt().plusSeconds(1), event.type(),
                        event.causationId(), event.correlationId(), event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(), event.planId(),
                        event.sequence(), event.occurredAt(), new EventType("different-type"),
                        event.causationId(), event.correlationId(), event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(), event.planId(),
                        event.sequence(), event.occurredAt(), event.type(),
                        Optional.of(new EventId("cause")), event.correlationId(),
                        event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(), event.planId(),
                        event.sequence(), event.occurredAt(), event.type(),
                        event.causationId(), "different-correlation", event.payload())),
                requestWithEvent(scenario.request(), event(
                        event, event.id(), event.taskFrameId(), event.planId(),
                        event.sequence(), event.occurredAt(), event.type(),
                        event.causationId(), event.correlationId(),
                        new InlineEventPayload(new ObjectValue(
                                Map.of("message", new TextValue("different")))))),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, new TaskFrameId("different-task"), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(), checkpoint.planState(),
                        checkpoint.stepStates(), checkpoint.receiptReferences(),
                        checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), new PlanId("different-plan"),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(), checkpoint.planState(),
                        checkpoint.stepStates(), checkpoint.receiptReferences(),
                        checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        new PlanRevisionId("different-revision"),
                        checkpoint.revisionNumber(), checkpoint.lastEventSequence(),
                        checkpoint.planState(), checkpoint.stepStates(),
                        checkpoint.receiptReferences(), checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), 2, checkpoint.lastEventSequence(),
                        checkpoint.planState(), checkpoint.stepStates(),
                        checkpoint.receiptReferences(), checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(), 2,
                        checkpoint.planState(), checkpoint.stepStates(),
                        checkpoint.receiptReferences(), checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(), PlanExecutionState.PAUSED,
                        checkpoint.stepStates(), checkpoint.receiptReferences(),
                        checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(), checkpoint.planState(),
                        Map.of(), checkpoint.receiptReferences(), checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(), checkpoint.planState(),
                        checkpoint.stepStates(), List.of(new ReceiptId("receipt")),
                        checkpoint.createdAt())),
                requestWithCheckpoint(scenario.request(), checkpoint(
                        checkpoint, checkpoint.taskFrameId(), checkpoint.planId(),
                        checkpoint.revisionId(), checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(), checkpoint.planState(),
                        checkpoint.stepStates(), checkpoint.receiptReferences(),
                        checkpoint.createdAt().plusSeconds(1))));

        for (ExecutionStartRequest conflict : conflicts) {
            assertFailureWithoutWrites(
                    scenario.persistence(),
                    conflict,
                    PersistenceErrorCode.CONFLICTING_REPLAY,
                    "request.planId");
        }
        assertEquals(observations, scenario.clock().observationCount());
    }

    @Test
    void nullRootAndLeaseFailuresFollowFrozenPriorityAndWriteNothing() {
        PersistenceFixtures.MutableCountingClock nullClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState nullState = new InMemoryState(nullClock);
        InMemoryExecutionStartRepository nullRepository =
                new InMemoryExecutionStartRepository(nullState);
        assertFailure(
                nullRepository.start(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request");
        assertFailure(
                nullRepository.start(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request");
        assertEquals(0, nullClock.observationCount());
        assertNoExecutionBusinessWrites(nullState);

        PersistenceFixtures.MutableCountingClock unknownClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence unknownPersistence = new InMemoryPersistence(unknownClock);
        ExecutionStartRequest unknown = new ExecutionStartRequest(
                new PlanId("unknown"),
                TOKEN,
                1,
                PersistenceFixtures.event(
                        "unknown-start",
                        PersistenceFixtures.TASK_ID,
                        new PlanId("unknown"),
                        1),
                PersistenceFixtures.startedCheckpoint(PersistenceFixtures.plan()));
        assertFailureWithoutWrites(
                unknownPersistence,
                unknown,
                PersistenceErrorCode.NOT_FOUND,
                "request.planId");
        assertEquals(2, unknownClock.observationCount());

        PersistenceFixtures.MutableCountingClock partialClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence partialPersistence =
                PersistenceFixtures.initializedPersistence(partialClock);
        partialPersistence.checkpoints().save(
                0, PersistenceFixtures.initialCheckpoint(PersistenceFixtures.plan()));
        ExecutionStartRequest partialRequest =
                PersistenceFixtures.executionStartRequest(
                        PersistenceFixtures.plan(), TOKEN, 1, "partial-start");
        assertFailureWithoutWrites(
                partialPersistence,
                partialRequest,
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(2, partialClock.observationCount());

        Scenario missingLease = bootstrappedScenario("missing-lease");
        assertStartFailureWithoutWrites(
                missingLease,
                missingLease.request(),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "request.planId");

        Scenario invalidLease = liveScenario("invalid-lease");
        invalidLease.clock().set(PersistenceFixtures.T0.plusSeconds(30));
        assertStartFailureWithoutWrites(
                invalidLease,
                new ExecutionStartRequest(
                        invalidLease.plan().id(),
                        "wrong-token",
                        99,
                        invalidLease.request().startEvent(),
                        invalidLease.request().startedCheckpoint()),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        assertStartFailureWithoutWrites(
                invalidLease,
                new ExecutionStartRequest(
                        invalidLease.plan().id(),
                        TOKEN,
                        99,
                        invalidLease.request().startEvent(),
                        invalidLease.request().startedCheckpoint()),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        assertStartFailureWithoutWrites(
                invalidLease,
                invalidLease.request(),
                PersistenceErrorCode.LEASE_EXPIRED,
                "request.planId");
    }

    @Test
    void missingPlanDistinguishesTrueAbsenceFromPlanScopedOrphans() {
        Plan orphanPlan = PersistenceFixtures.plan(
                new PlanId("orphan-plan"),
                PersistenceFixtures.TASK_ID,
                "orphan");
        ExecutionStartRequest request = PersistenceFixtures.executionStartRequest(
                orphanPlan, TOKEN, 1, "orphan-start");
        List<Consumer<InMemoryState>> occupiers = List.of(
                state -> state.planBootstraps.put(
                        orphanPlan.id(),
                        new PersistedPlanBootstrap(
                                PersistenceFixtures.taskFrame(),
                                orphanPlan,
                                new VersionedCheckpoint(
                                        1,
                                        PersistenceFixtures.initialCheckpoint(orphanPlan)))),
                state -> state.checkpoints.put(
                        orphanPlan.id(),
                        new VersionedCheckpoint(
                                1,
                                PersistenceFixtures.initialCheckpoint(orphanPlan))),
                state -> state.eventStreams.put(orphanPlan.id(), new TreeMap<>()),
                state -> {
                    EventEnvelope event = PersistenceFixtures.event(
                            "orphan-index-entry",
                            orphanPlan.taskFrameId(),
                            orphanPlan.id(),
                            1);
                    state.eventsById.put(event.id(), event);
                },
                state -> state.leases.put(
                        orphanPlan.id(),
                        new LeaseRecord(
                                orphanPlan.id(),
                                OWNER,
                                TOKEN,
                                1,
                                PersistenceFixtures.T0,
                                PersistenceFixtures.T0.plusSeconds(30))),
                state -> state.fencingTokens.put(orphanPlan.id(), 1L),
                state -> {
                    var spec = PersistenceFixtures.workspaceSpec(
                            "orphan-start");
                    state.workspaceOwners.put(
                            spec.workspaceId(),
                            new InMemoryState.WorkspaceOwner(
                                    orphanPlan.id(), spec));
                });

        for (Consumer<InMemoryState> occupy : occupiers) {
            InMemoryState state = new InMemoryState(
                    new PersistenceFixtures.MutableCountingClock(
                            PersistenceFixtures.T0));
            occupy.accept(state);
            assertDirectFailureWithoutWrites(
                    state,
                    request,
                    PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                    "executionStart");
        }

        InMemoryState unrelated = new InMemoryState(
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0));
        unrelated.taskFrames.put(
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.taskFrame());
        var receipt = PersistenceFixtures.receipt(
                "unrelated-receipt", "unrelated-tool-call");
        unrelated.receipts.put(receipt.id(), receipt);
        unrelated.usedLeaseTokens.add("unrelated-used-token");
        IdempotencyKey idempotencyKey =
                new IdempotencyKey("unrelated-scope", "unrelated-key");
        unrelated.idempotency.put(
                idempotencyKey,
                new IdempotencyRecord(
                        idempotencyKey,
                        "unrelated-fingerprint",
                        IdempotencyState.IN_PROGRESS,
                        Optional.empty()));
        assertDirectFailureWithoutWrites(
                unrelated,
                request,
                PersistenceErrorCode.NOT_FOUND,
                "request.planId");
    }

    @Test
    void liveCheckpointIsCheckedOnlyAfterLeaseValidation() {
        Scenario scenario = bootstrappedScenario("live-checkpoint");
        Checkpoint advanced = checkpoint(
                scenario.request().startedCheckpoint(),
                scenario.plan().taskFrameId(),
                scenario.plan().id(),
                scenario.plan().latestRevision().id(),
                scenario.plan().latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                scenario.source().checkpoint().stepStates(),
                List.of(),
                PersistenceFixtures.T0.plusSeconds(1));
        assertEquals(
                PersistenceOutcome.APPLIED,
                scenario.persistence().checkpoints().save(1, advanced).outcome());

        assertFailureWithoutWrites(
                scenario.persistence(),
                scenario.request(),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "request.planId");
        LeaseRecord lease = scenario.persistence().leases().acquire(
                scenario.plan().id(),
                OWNER,
                "actual-token",
                PersistenceFixtures.T0.plusSeconds(30))
                .value()
                .orElseThrow();
        assertFailureWithoutWrites(
                scenario.persistence(),
                scenario.request(),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        ExecutionStartRequest currentLeaseRequest = new ExecutionStartRequest(
                scenario.request().planId(),
                lease.leaseToken(),
                lease.fencingToken(),
                scenario.request().startEvent(),
                scenario.request().startedCheckpoint());
        assertFailureWithoutWrites(
                scenario.persistence(),
                currentLeaseRequest,
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(
                new VersionedCheckpoint(2, advanced),
                scenario.persistence().checkpoints()
                        .find(scenario.plan().id()).value().orElseThrow());
        assertTrue(scenario.persistence().events()
                .readAfter(scenario.plan().id(), 0).value().orElseThrow().isEmpty());
    }

    @Test
    void inconsistentBootstrapHistoryPrefixIsPartialBeforeLeaseValidation() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan storedPlan = PersistenceFixtures.plan();
        Plan inconsistentMarkerPlan = PersistenceFixtures.plan(
                storedPlan.id(), storedPlan.taskFrameId(), "inconsistent-marker");
        VersionedCheckpoint markerSource = new VersionedCheckpoint(
                1, PersistenceFixtures.initialCheckpoint(inconsistentMarkerPlan));
        state.taskFrames.put(taskFrame.id(), taskFrame);
        state.plans.put(storedPlan.id(), storedPlan);
        state.planBootstraps.put(
                storedPlan.id(),
                new PersistedPlanBootstrap(
                        taskFrame, inconsistentMarkerPlan, markerSource));
        VersionedCheckpoint liveSource = new VersionedCheckpoint(
                1, PersistenceFixtures.initialCheckpoint(storedPlan));
        state.checkpoints.put(storedPlan.id(), liveSource);
        ExecutionStartRequest request =
                PersistenceFixtures.executionStartRequest(
                        storedPlan, TOKEN, 1, "inconsistent-marker-start");

        assertDirectFailureWithoutWrites(
                state,
                request,
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(2, clock.observationCount());
        assertEquals(liveSource, state.checkpoints.get(storedPlan.id()));
        assertTrue(state.eventsById.isEmpty());
        assertTrue(state.eventStreams.isEmpty());
        assertTrue(state.executionStarts.isEmpty());
    }

    @Test
    void requestBindingAndCanonicalFailuresHaveStableMappings() {
        Scenario eventPlan = liveScenario("event-plan");
        assertStartFailureWithoutWrites(
                eventPlan,
                requestWithEvent(
                        eventPlan.request(),
                        event(
                                eventPlan.request().startEvent(),
                                eventPlan.request().startEvent().id(),
                                new TaskFrameId("also-wrong-task"),
                                new PlanId("other-plan"),
                                2,
                                eventPlan.request().startEvent().occurredAt(),
                                eventPlan.request().startEvent().type(),
                                eventPlan.request().startEvent().causationId(),
                                eventPlan.request().startEvent().correlationId(),
                                eventPlan.request().startEvent().payload())),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request.startEvent.planId");

        Scenario eventTask = liveScenario("event-task");
        assertStartFailureWithoutWrites(
                eventTask,
                requestWithEvent(
                        eventTask.request(),
                        event(
                                eventTask.request().startEvent(),
                                eventTask.request().startEvent().id(),
                                new TaskFrameId("other-task"),
                                eventTask.plan().id(),
                                2,
                                eventTask.request().startEvent().occurredAt(),
                                eventTask.request().startEvent().type(),
                                eventTask.request().startEvent().causationId(),
                                eventTask.request().startEvent().correlationId(),
                                eventTask.request().startEvent().payload())),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "request.startEvent.taskFrameId");

        Scenario sequence = liveScenario("event-sequence");
        assertStartFailureWithoutWrites(
                sequence,
                requestWithEvent(
                        sequence.request(),
                        PersistenceFixtures.event(
                                "event-sequence",
                                sequence.plan().taskFrameId(),
                                sequence.plan().id(),
                                2)),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                "request.startEvent.sequence");

        Scenario cursor = liveScenario("checkpoint-cursor");
        assertStartFailureWithoutWrites(
                cursor,
                requestWithCheckpoint(
                        cursor.request(),
                        checkpoint(
                                cursor.request().startedCheckpoint(),
                                cursor.plan().taskFrameId(),
                                new PlanId("also-wrong-plan"),
                                cursor.plan().latestRevision().id(),
                                cursor.plan().latestRevision().number(),
                                0,
                                PlanExecutionState.ACTIVE,
                                cursor.request().startedCheckpoint().stepStates(),
                                List.of(),
                                cursor.request().startedCheckpoint().createdAt())),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint.lastEventSequence");

        assertGenericCanonicalFailure(
                "checkpoint-plan",
                checkpoint -> checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        new PlanId("other-plan"),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        1,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()));
        assertGenericCanonicalFailure(
                "checkpoint-task",
                checkpoint -> checkpoint(
                        checkpoint,
                        new TaskFrameId("other-task"),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        1,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()));
        assertGenericCanonicalFailure(
                "checkpoint-revision",
                checkpoint -> checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        new PlanRevisionId("other-revision"),
                        checkpoint.revisionNumber(),
                        1,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()));
        assertGenericCanonicalFailure(
                "checkpoint-revision-number",
                checkpoint -> checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        2,
                        1,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()));
        assertGenericCanonicalFailure(
                "checkpoint-state",
                checkpoint -> checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        1,
                        PlanExecutionState.PAUSED,
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()));
        assertGenericCanonicalFailure(
                "checkpoint-steps",
                checkpoint -> {
                    Map<PlanStepId, StepExecutionState> states =
                            new LinkedHashMap<>(checkpoint.stepStates());
                    states.put(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE);
                    return checkpoint(
                            checkpoint,
                            checkpoint.taskFrameId(),
                            checkpoint.planId(),
                            checkpoint.revisionId(),
                            checkpoint.revisionNumber(),
                            1,
                            checkpoint.planState(),
                            states,
                            checkpoint.receiptReferences(),
                            checkpoint.createdAt());
                });
        assertGenericCanonicalFailure(
                "checkpoint-receipts",
                checkpoint -> checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        1,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        List.of(new ReceiptId("receipt")),
                        checkpoint.createdAt()));
        assertGenericCanonicalFailure(
                "checkpoint-history",
                checkpoint -> checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        1,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        PersistenceFixtures.T0.minusSeconds(1)));
    }

    @Test
    void latestRevisionMustRemainPreExecutionAndCandidateMustBindIt() {
        Scenario scenario = liveScenario("latest-revision");
        PlanRevision revision2 = PersistenceFixtures.revision2(
                "revision-2", "pre-execution refinement");
        Plan updated = scenario.persistence().plans()
                .appendRevision(scenario.plan().id(), 1, revision2)
                .value().orElseThrow();

        assertStartFailureWithoutWrites(
                scenario,
                scenario.request(),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint");

        ExecutionStartRequest latestRequest =
                PersistenceFixtures.executionStartRequest(
                        updated, TOKEN, 1, "latest-revision-start");
        assertEquals(
                PersistenceOutcome.APPLIED,
                scenario.persistence().executionStarts().start(latestRequest).outcome());

        Scenario completed = liveScenario("completed-fact");
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "outcome-hash",
                PersistenceFixtures.T0.plusSeconds(1),
                List.of());
        PlanRevision completedRevision = new PlanRevision(
                new PlanRevisionId("revision-completed"),
                completed.plan().taskFrameId(),
                2,
                Optional.of(completed.plan().latestRevision().id()),
                "invalid pre-execution completion",
                PersistenceFixtures.T0.plusSeconds(1),
                completed.plan().latestRevision().steps(),
                Map.of(PersistenceFixtures.STEP_1, fact));
        Plan completedPlan = completed.persistence().plans()
                .appendRevision(completed.plan().id(), 1, completedRevision)
                .value().orElseThrow();
        assertStartFailureWithoutWrites(
                completed,
                PersistenceFixtures.executionStartRequest(
                        completedPlan, TOKEN, 1, "completed-start"),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint");
    }

    @Test
    void occupancyPriorityIsGlobalEventIdThenTargetStream() {
        Scenario scenario = liveScenario("occupancy-cross");
        PlanId otherPlanId = new PlanId("occupancy-other");
        Plan otherPlan = PersistenceFixtures.plan(otherPlanId);
        assertEquals(
                PersistenceOutcome.APPLIED,
                scenario.persistence().plans().create(otherPlan).outcome());
        EventEnvelope crossPlanEvent = PersistenceFixtures.event(
                scenario.request().startEvent().id().value(),
                otherPlan.taskFrameId(),
                otherPlan.id(),
                1);
        EventEnvelope targetEvent = PersistenceFixtures.event(
                "target-occupied",
                scenario.plan().taskFrameId(),
                scenario.plan().id(),
                1);
        scenario.persistence().events().append(crossPlanEvent);
        scenario.persistence().events().append(targetEvent);
        VersionedCheckpoint source = scenario.persistence().checkpoints()
                .find(scenario.plan().id()).value().orElseThrow();
        PersistenceResult<EventEnvelope> crossPlanEventBefore =
                scenario.persistence().events().find(crossPlanEvent.id());
        PersistenceResult<List<EventEnvelope>> targetStreamBefore =
                scenario.persistence().events().readAfter(scenario.plan().id(), 0);

        assertFailureWithoutWrites(
                scenario.persistence(),
                scenario.request(),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.startEvent.id");
        assertEquals(
                source,
                scenario.persistence().checkpoints()
                        .find(scenario.plan().id()).value().orElseThrow());
        assertEquals(
                crossPlanEventBefore,
                scenario.persistence().events().find(crossPlanEvent.id()));
        assertEquals(
                targetStreamBefore,
                scenario.persistence().events().readAfter(scenario.plan().id(), 0));

        Scenario streamOnly = liveScenario("occupancy-stream");
        streamOnly.persistence().events().append(PersistenceFixtures.event(
                "already-there",
                streamOnly.plan().taskFrameId(),
                streamOnly.plan().id(),
                1));
        assertFailureWithoutWrites(
                streamOnly.persistence(),
                streamOnly.request(),
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
    }

    @Test
    void exactReplaySurvivesLeaseAndDownstreamStateChanges() {
        Scenario scenario = liveScenario("stable-replay", 10);
        PersistedExecutionStart original = scenario.persistence().executionStarts()
                .start(scenario.request()).value().orElseThrow();

        scenario.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        LeaseRecord takeover = scenario.persistence().leases().acquire(
                        scenario.plan().id(),
                        "worker-b",
                        "lease-token-b",
                        PersistenceFixtures.T0.plusSeconds(30))
                .value().orElseThrow();
        assertEquals(2, takeover.fencingToken());
        StepActivationRequest activation =
                PersistenceFixtures.stepActivationRequest(
                        scenario.plan(),
                        "lease-token-b",
                        takeover.fencingToken(),
                        "after-start");
        PersistenceFixtures.confirmExecutionContext(
                scenario.persistence().planExecutionContexts(),
                scenario.plan(),
                "lease-token-b",
                takeover.fencingToken(),
                PersistenceFixtures.workspaceSpec("start-replay"));
        PersistedStepActivation advanced = scenario.persistence()
                .stepActivations().activate(activation)
                .value().orElseThrow();
        scenario.persistence().leases().release(
                scenario.plan().id(), "lease-token-b");

        int observations = scenario.clock().observationCount();
        scenario.clock().failOnObservation();
        PersistenceResult<PersistedExecutionStart> replay =
                scenario.persistence().executionStarts().start(scenario.request());
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(original, replay.value().orElseThrow());
        assertEquals(observations, scenario.clock().observationCount());
        assertEquals(
                advanced.activatedCheckpoint(),
                scenario.persistence().checkpoints()
                        .find(scenario.plan().id()).value().orElseThrow());
    }

    private static Scenario liveScenario(String eventId) {
        return liveScenario(eventId, 30);
    }

    private static Scenario liveScenario(String eventId, long leaseSeconds) {
        Scenario scenario = bootstrappedScenario(eventId);
        LeaseRecord lease = scenario.persistence().leases().acquire(
                        scenario.plan().id(),
                        OWNER,
                        TOKEN,
                        PersistenceFixtures.T0.plusSeconds(leaseSeconds))
                .value().orElseThrow();
        return new Scenario(
                scenario.clock(),
                scenario.persistence(),
                scenario.plan(),
                scenario.source(),
                PersistenceFixtures.executionStartRequest(
                        scenario.plan(), TOKEN, lease.fencingToken(), eventId));
    }

    private static Scenario bootstrappedScenario(String eventId) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence persistence =
                PersistenceFixtures.bootstrappedPersistence(clock);
        Plan plan = PersistenceFixtures.plan();
        VersionedCheckpoint source = persistence.checkpoints()
                .find(plan.id()).value().orElseThrow();
        return new Scenario(
                clock,
                persistence,
                plan,
                source,
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, 1, eventId));
    }

    private static void assertStartFailureWithoutWrites(
            Scenario scenario,
            ExecutionStartRequest request,
            PersistenceErrorCode code,
            String path) {
        int observations = scenario.clock().observationCount();

        assertFailureWithoutWrites(
                scenario.persistence(),
                request,
                code,
                path);
        assertEquals(observations + 2, scenario.clock().observationCount());
    }

    private static void assertFailureWithoutWrites(
            InMemoryPersistence persistence,
            ExecutionStartRequest request,
            PersistenceErrorCode code,
            String path) {
        BusinessStateSnapshot before = BusinessStateSnapshot.capture(
                persistence.checkpoints(),
                persistence.events(),
                request);

        assertFailure(
                persistence.executionStarts().start(request),
                code,
                path);
        assertEquals(
                before,
                BusinessStateSnapshot.capture(
                        persistence.checkpoints(),
                        persistence.events(),
                        request));
        assertFailure(
                persistence.executionStarts().start(request),
                code,
                path);
        assertEquals(
                before,
                BusinessStateSnapshot.capture(
                        persistence.checkpoints(),
                        persistence.events(),
                        request));
    }

    private static void assertDirectFailureWithoutWrites(
            InMemoryState state,
            ExecutionStartRequest request,
            PersistenceErrorCode code,
            String path) {
        CheckpointRepository checkpoints = new InMemoryCheckpointRepository(state);
        EventRepository events = new InMemoryEventRepository(state);
        ExecutionStartRepository starts =
                new InMemoryExecutionStartRepository(state);
        BusinessStateSnapshot before =
                BusinessStateSnapshot.capture(checkpoints, events, request);
        Map<EventId, EventEnvelope> eventIndexBefore =
                Map.copyOf(state.eventsById);
        Map<PlanId, List<EventEnvelope>> streamsBefore =
                eventStreamsSnapshot(state);
        Map<PlanId, InMemoryState.ExecutionStartMarker> markersBefore =
                Map.copyOf(state.executionStarts);

        assertFailure(starts.start(request), code, path);
        assertEquals(
                before,
                BusinessStateSnapshot.capture(checkpoints, events, request));
        assertEquals(eventIndexBefore, state.eventsById);
        assertEquals(streamsBefore, eventStreamsSnapshot(state));
        assertEquals(markersBefore, state.executionStarts);
        assertFailure(starts.start(request), code, path);
        assertEquals(
                before,
                BusinessStateSnapshot.capture(checkpoints, events, request));
        assertEquals(eventIndexBefore, state.eventsById);
        assertEquals(streamsBefore, eventStreamsSnapshot(state));
        assertEquals(markersBefore, state.executionStarts);
    }

    private static Map<PlanId, List<EventEnvelope>> eventStreamsSnapshot(
            InMemoryState state) {
        Map<PlanId, List<EventEnvelope>> snapshot = new LinkedHashMap<>();
        state.eventStreams.forEach(
                (planId, stream) ->
                        snapshot.put(planId, List.copyOf(stream.values())));
        return Map.copyOf(snapshot);
    }

    private static void assertNoExecutionBusinessWrites(InMemoryState state) {
        assertTrue(state.eventsById.isEmpty());
        assertTrue(state.eventStreams.isEmpty());
        assertTrue(state.checkpoints.isEmpty());
        assertTrue(state.executionStarts.isEmpty());
    }

    private static void assertGenericCanonicalFailure(
            String suffix,
            java.util.function.UnaryOperator<Checkpoint> mutation) {
        Scenario scenario = liveScenario(suffix);
        assertStartFailureWithoutWrites(
                scenario,
                requestWithCheckpoint(
                        scenario.request(),
                        mutation.apply(scenario.request().startedCheckpoint())),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint");
    }

    private static ExecutionStartRequest requestWithEvent(
            ExecutionStartRequest request,
            EventEnvelope event) {
        return new ExecutionStartRequest(
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                event,
                request.startedCheckpoint());
    }

    private static ExecutionStartRequest requestWithCheckpoint(
            ExecutionStartRequest request,
            Checkpoint checkpoint) {
        return new ExecutionStartRequest(
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                request.startEvent(),
                checkpoint);
    }

    private static EventEnvelope event(
            EventEnvelope ignored,
            EventId id,
            TaskFrameId taskFrameId,
            PlanId planId,
            long sequence,
            Instant occurredAt,
            EventType type,
            Optional<EventId> causationId,
            String correlationId,
            io.paperagent.v2.contracts.EventPayload payload) {
        return new EventEnvelope(
                id,
                taskFrameId,
                planId,
                sequence,
                occurredAt,
                type,
                causationId,
                correlationId,
                payload);
    }

    private static Checkpoint checkpoint(
            Checkpoint ignored,
            TaskFrameId taskFrameId,
            PlanId planId,
            PlanRevisionId revisionId,
            long revisionNumber,
            long lastEventSequence,
            PlanExecutionState planState,
            Map<PlanStepId, StepExecutionState> stepStates,
            List<ReceiptId> receipts,
            Instant createdAt) {
        return new Checkpoint(
                taskFrameId,
                planId,
                revisionId,
                revisionNumber,
                lastEventSequence,
                planState,
                stepStates,
                receipts,
                createdAt);
    }

    private static void assertNullMessage(Runnable constructor, String message) {
        NullPointerException failure =
                assertThrows(NullPointerException.class, constructor::run);
        assertEquals(message, failure.getMessage());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertTrue(result.value().isEmpty());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record BusinessStateSnapshot(
            PersistenceResult<VersionedCheckpoint> checkpoint,
            PersistenceResult<List<EventEnvelope>> eventStream,
            PersistenceResult<EventEnvelope> eventById) {

        private static BusinessStateSnapshot capture(
                CheckpointRepository checkpoints,
                EventRepository events,
                ExecutionStartRequest request) {
            return new BusinessStateSnapshot(
                    checkpoints.find(request.planId()),
                    events.readAfter(request.planId(), 0),
                    events.find(request.startEvent().id()));
        }
    }

    private record Scenario(
            PersistenceFixtures.MutableCountingClock clock,
            InMemoryPersistence persistence,
            Plan plan,
            VersionedCheckpoint source,
            ExecutionStartRequest request) {
    }
}
