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
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRecoveryRepositoryTest {
    private static final String OWNER = "runtime-owner";
    private static final String TOKEN = "UNIQUE-RECOVERY-TOKEN-741";

    @Test
    void validatesInputAndDistinguishesUnknownAndIndependentPlans() {
        Harness harness = harness();

        assertFailure(
                harness.recovery().inspect(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(
                harness.recovery().inspect(new PlanId("unknown")),
                PersistenceErrorCode.NOT_FOUND,
                "planId");

        Plan secondPlan = PersistenceFixtures.plan(
                new PlanId("plan-2"),
                PersistenceFixtures.TASK_ID,
                "second");
        requireApplied(harness.bootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                secondPlan,
                PersistenceFixtures.initialCheckpoint(secondPlan)));
        PersistenceResult<ExecutionStartRecoverySnapshot> second =
                harness.recovery().inspect(secondPlan.id());
        assertEquals(PersistenceOutcome.FOUND, second.outcome());
        assertEquals(secondPlan.id(), second.value().orElseThrow().planId());
        assertFailure(
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
    }

    @Test
    void canonicalBootstrapAndPreStartRevisionAreStrictReady() {
        Harness harness = bootstrappedHarness();

        PersistenceResult<ExecutionStartRecoverySnapshot> initial =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);
        assertEquals(PersistenceOutcome.FOUND, initial.outcome());
        PersistedExecutionStartReady ready =
                (PersistedExecutionStartReady) initial.value().orElseThrow();
        assertEquals(PersistenceFixtures.plan(), ready.currentPlan());
        assertEquals(PersistenceFixtures.PLAN_ID, ready.planId());

        PlanRevision revision2 =
                PersistenceFixtures.revision2("revision-2", "pre-start refinement");
        Plan revised = requireApplied(harness.plans().appendRevision(
                PersistenceFixtures.PLAN_ID, 1, revision2));
        PersistedExecutionStartReady revisedReady =
                (PersistedExecutionStartReady) harness.recovery()
                        .inspect(PersistenceFixtures.PLAN_ID)
                        .value()
                        .orElseThrow();
        assertEquals(revised, revisedReady.currentPlan());
    }

    @Test
    void absentAndExplicitEmptyStreamsAreReadyButNullStreamIsPartial() {
        Harness absent = bootstrappedHarness();
        assertTrue(absent.recovery()
                        .inspect(PersistenceFixtures.PLAN_ID)
                        .value()
                        .orElseThrow()
                instanceof PersistedExecutionStartReady);

        Harness explicitEmpty = bootstrappedHarness();
        explicitEmpty.state().eventStreams.put(
                PersistenceFixtures.PLAN_ID, new TreeMap<>());
        assertTrue(explicitEmpty.recovery()
                        .inspect(PersistenceFixtures.PLAN_ID)
                        .value()
                        .orElseThrow()
                instanceof PersistedExecutionStartReady);

        Harness nullStream = bootstrappedHarness();
        nullStream.state().eventStreams.put(PersistenceFixtures.PLAN_ID, null);
        assertPartial(nullStream.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness committedNullStream = bootstrappedHarness();
        start(committedNullStream, TOKEN, "start-null-stream");
        committedNullStream.state().eventStreams.put(
                PersistenceFixtures.PLAN_ID, null);
        assertPartial(committedNullStream.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void leaseHistoryDoesNotChangeReadyOrObserveClock() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness harness = bootstrappedHarness(clock);
        requireApplied(harness.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                OWNER,
                TOKEN,
                PersistenceFixtures.T0.plusSeconds(20)));
        requireApplied(harness.leases().release(
                PersistenceFixtures.PLAN_ID, TOKEN));
        requireApplied(harness.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                OWNER,
                "ready-expiring-token",
                PersistenceFixtures.T0.plusSeconds(30)));
        clock.set(PersistenceFixtures.T0.plusSeconds(40));
        requireApplied(harness.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "takeover-owner",
                "ready-takeover-token",
                PersistenceFixtures.T0.plusSeconds(60)));
        int before = clock.observationCount();
        clock.failOnObservation();

        PersistenceResult<ExecutionStartRecoverySnapshot> inspected =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);

        assertEquals(PersistenceOutcome.FOUND, inspected.outcome());
        assertTrue(inspected.value().orElseThrow()
                instanceof PersistedExecutionStartReady);
        assertEquals(before, clock.observationCount());
    }

    @Test
    void orphanOccupancyAndMarkerlessLookalikesArePartial() {
        PersistenceFixtures.MutableCountingClock leaseClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness leaseOnly = harness(leaseClock);
        leaseOnly.state().leases.put(
                PersistenceFixtures.PLAN_ID,
                new LeaseRecord(
                        PersistenceFixtures.PLAN_ID,
                        OWNER,
                        TOKEN,
                        1,
                        PersistenceFixtures.T0,
                        PersistenceFixtures.T0.plusSeconds(20)));
        int leaseObservations = leaseClock.observationCount();
        assertPartial(leaseOnly.recovery().inspect(PersistenceFixtures.PLAN_ID));
        assertEquals(leaseObservations, leaseClock.observationCount());

        PersistenceFixtures.MutableCountingClock fencingClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness fencingOnly = harness(fencingClock);
        fencingOnly.state().fencingTokens.put(PersistenceFixtures.PLAN_ID, 9L);
        int fencingObservations = fencingClock.observationCount();
        assertPartial(fencingOnly.recovery().inspect(PersistenceFixtures.PLAN_ID));
        assertEquals(fencingObservations, fencingClock.observationCount());

        Harness contextOnly = harness();
        var orphanSpec = PersistenceFixtures.workspaceSpec(
                "recovery-owner-only");
        contextOnly.state().workspaceOwners.put(
                orphanSpec.workspaceId(),
                new InMemoryState.WorkspaceOwner(
                        PersistenceFixtures.PLAN_ID, orphanSpec));
        assertPartial(contextOnly.recovery().inspect(
                PersistenceFixtures.PLAN_ID));

        Harness lookalike = harness();
        requireApplied(lookalike.taskFrames().create(PersistenceFixtures.taskFrame()));
        requireApplied(lookalike.plans().create(PersistenceFixtures.plan()));
        requireApplied(lookalike.checkpoints().save(
                0, PersistenceFixtures.initialCheckpoint(PersistenceFixtures.plan())));
        assertPartial(lookalike.recovery().inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void markerlessProgressAndCompletionFactsArePartial() {
        Harness eventProgress = bootstrappedHarness();
        requireApplied(eventProgress.events().append(PersistenceFixtures.event("event-1", 1)));
        assertPartial(eventProgress.recovery().inspect(PersistenceFixtures.PLAN_ID));

        Harness checkpointProgress = bootstrappedHarness();
        checkpointProgress.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                new VersionedCheckpoint(
                        2,
                        PersistenceFixtures.startedCheckpoint(
                                PersistenceFixtures.plan())));
        assertPartial(checkpointProgress.recovery().inspect(PersistenceFixtures.PLAN_ID));

        Harness facts = bootstrappedHarness();
        PlanRevision withFact = revisionWithFact(new ReceiptId("receipt-missing"));
        requireApplied(facts.plans().appendRevision(
                PersistenceFixtures.PLAN_ID, 1, withFact));
        assertPartial(facts.recovery().inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void atomicStartAndResponseLossAreStrictCommitted() {
        Harness harness = bootstrappedHarness();
        PersistedExecutionStart original = start(harness, TOKEN, "start-event");

        PersistenceResult<ExecutionStartRecoverySnapshot> first =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);
        PersistenceResult<ExecutionStartRecoverySnapshot> afterResponseLoss =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);

        assertEquals(PersistenceOutcome.FOUND, first.outcome());
        PersistedExecutionStartCommitted committed =
                (PersistedExecutionStartCommitted) first.value().orElseThrow();
        assertSame(original, committed.executionStart());
        assertEquals(committed, afterResponseLoss.value().orElseThrow());
        assertNotSame(first, afterResponseLoss);
    }

    @Test
    void executionStartedAfterPreStartRevisionRemainsStrictCommitted() {
        Harness harness = bootstrappedHarness();
        PlanRevision revision2 = PersistenceFixtures.revision2(
                "revision-2", "pre-start revision");
        Plan revised = requireApplied(harness.plans().appendRevision(
                PersistenceFixtures.PLAN_ID, 1, revision2));

        PersistedExecutionStart started = start(
                harness, TOKEN, "start-pre-start-revision", revised);

        PersistenceResult<ExecutionStartRecoverySnapshot> inspected =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);
        assertEquals(PersistenceOutcome.FOUND, inspected.outcome());
        PersistedExecutionStartCommitted committed =
                (PersistedExecutionStartCommitted) inspected.value().orElseThrow();
        assertEquals(revised, committed.currentPlan());
        assertEquals(revision2.id(), started.startedCheckpoint().checkpoint().revisionId());
    }

    @Test
    void leaseReleaseAndFailingClockDoNotChangeCommitted() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness harness = bootstrappedHarness(clock);
        start(harness, TOKEN, "start-event");
        requireApplied(harness.leases().release(
                PersistenceFixtures.PLAN_ID, TOKEN));
        int before = clock.observationCount();
        clock.failOnObservation();

        PersistenceResult<ExecutionStartRecoverySnapshot> inspected =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);

        assertEquals(PersistenceOutcome.FOUND, inspected.outcome());
        assertTrue(inspected.value().orElseThrow()
                instanceof PersistedExecutionStartCommitted);
        assertEquals(before, clock.observationCount());
    }

    @Test
    void expiryAndTakeoverDoNotChangeCommittedOrObserveClock() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness harness = bootstrappedHarness(clock);
        start(harness, TOKEN, "start-takeover");
        clock.set(PersistenceFixtures.T0.plusSeconds(70));
        requireApplied(harness.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "takeover-owner",
                "committed-takeover-token",
                PersistenceFixtures.T0.plusSeconds(100)));
        int before = clock.observationCount();
        clock.failOnObservation();

        PersistenceResult<ExecutionStartRecoverySnapshot> inspected =
                harness.recovery().inspect(PersistenceFixtures.PLAN_ID);

        assertEquals(PersistenceOutcome.FOUND, inspected.outcome());
        assertTrue(inspected.value().orElseThrow()
                instanceof PersistedExecutionStartCommitted);
        assertEquals(before, clock.observationCount());
    }

    @Test
    void returnedPlanHistoryStepsAndFactsRemainImmutable() {
        Harness harness = bootstrappedHarness();
        PersistedExecutionStartReady ready =
                (PersistedExecutionStartReady) harness.recovery()
                        .inspect(PersistenceFixtures.PLAN_ID)
                        .value()
                        .orElseThrow();

        assertThrows(
                UnsupportedOperationException.class,
                () -> ready.currentPlan().revisions().add(
                        PersistenceFixtures.revision2(
                                "revision-extra", "not allowed")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ready.currentPlan().latestRevision().steps().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ready.currentPlan().latestRevision()
                        .completedFacts()
                        .put(
                                PersistenceFixtures.STEP_1,
                                new CompletionFact(
                                        PersistenceFixtures.STEP_1,
                                        "forged",
                                        PersistenceFixtures.T0,
                                        List.of())));

        start(harness, TOKEN, "start-immutable");
        PersistedExecutionStartCommitted committed =
                (PersistedExecutionStartCommitted) harness.recovery()
                        .inspect(PersistenceFixtures.PLAN_ID)
                        .value()
                        .orElseThrow();
        assertThrows(
                UnsupportedOperationException.class,
                () -> committed.currentPlan().revisions().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> committed.executionStart()
                        .startedCheckpoint()
                        .checkpoint()
                        .stepStates()
                        .clear());
    }

    @Test
    void snapshotsAndPersistenceResultsKeepMarkerTokenAndBusinessValuesOpaque() {
        String secret = "TOKEN-IN-PLAN-AND-EVENT-991";
        Plan secretPlan = PersistenceFixtures.plan(
                new PlanId(secret),
                PersistenceFixtures.TASK_ID,
                "secret");
        Harness harness = harness();
        PersistedPlanBootstrap bootstrap = requireApplied(
                harness.bootstraps().bootstrap(
                        PersistenceFixtures.taskFrame(),
                        secretPlan,
                        PersistenceFixtures.initialCheckpoint(secretPlan)));
        PersistenceResult<ExecutionStartRecoverySnapshot> ready =
                harness.recovery().inspect(secretPlan.id());
        assertOpaque(ready.toString(), secret);

        requireApplied(harness.leases().acquire(
                secretPlan.id(),
                OWNER,
                secret,
                PersistenceFixtures.T0.plusSeconds(20)));
        EventEnvelope secretEvent = new EventEnvelope(
                new EventId(secret),
                secretPlan.taskFrameId(),
                secretPlan.id(),
                1,
                PersistenceFixtures.T0.plusSeconds(1),
                new EventType("execution-start"),
                Optional.empty(),
                secret,
                new InlineEventPayload(new ObjectValue(
                        Map.of("sensitive", new TextValue(secret)))));
        ExecutionStartRequest request = new ExecutionStartRequest(
                secretPlan.id(),
                secret,
                1,
                secretEvent,
                PersistenceFixtures.startedCheckpoint(secretPlan));
        requireApplied(harness.executionStarts().start(request));
        PersistenceResult<ExecutionStartRecoverySnapshot> committed =
                harness.recovery().inspect(secretPlan.id());

        assertOpaque(committed.toString(), secret);
        assertOpaque(committed.value().orElseThrow().toString(), secret);
        assertFalse(bootstrap.toString().isBlank());
    }

    @Test
    void validFencedActivationProgressIsAdvanced() {
        Harness checkpointAdvanced = bootstrappedHarness();
        PersistedExecutionStart started =
                start(checkpointAdvanced, TOKEN, "start-checkpoint");
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(
                        checkpointAdvanced.state()),
                PersistenceFixtures.plan(),
                TOKEN,
                started.fencingToken(),
                PersistenceFixtures.workspaceSpec("recovery-advanced"));
        requireApplied(checkpointAdvanced.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        started.fencingToken(),
                        "activation-3")));
        assertAdvanced(checkpointAdvanced.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void validFencedReplanProgressIsAdvanced() {
        Harness harness = bootstrappedHarness();
        PersistedExecutionStart started = start(harness, TOKEN, "start-replan-chain");
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(harness.state()),
                PersistenceFixtures.plan(),
                TOKEN,
                started.fencingToken(),
                PersistenceFixtures.workspaceSpec("recovery-replan-chain"));
        Plan source = harness.state().plans.get(PersistenceFixtures.PLAN_ID);
        Checkpoint current = harness.state().checkpoints
                .get(PersistenceFixtures.PLAN_ID).checkpoint();
        PlanRevision replanned = new PlanRevision(
                new PlanRevisionId("revision-recovery-replan"),
                source.taskFrameId(),
                2,
                Optional.of(source.latestRevision().id()),
                "recovery inspection replan",
                current.createdAt().plusSeconds(1),
                source.latestRevision().steps(),
                source.latestRevision().completedFacts());
        Checkpoint replannedCheckpoint = new Checkpoint(
                source.taskFrameId(),
                source.id(),
                replanned.id(),
                replanned.number(),
                2,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.NOT_STARTED,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                current.receiptReferences(),
                current.createdAt().plusSeconds(1));
        requireApplied(new InMemoryPlanReplanRepository(harness.state()).replan(
                new PlanReplanRequest(
                        source.id(),
                        TOKEN,
                        started.fencingToken(),
                        source.latestRevision().id(),
                        source.latestRevision().number(),
                        2,
                        1,
                        PersistenceFixtures.event(
                                "replan-recovery-chain",
                                source.taskFrameId(),
                                source.id(),
                                2),
                        replanned,
                        replannedCheckpoint)));

        assertAdvanced(harness.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void validActivationCompletionActivationChainIsAdvanced() {
        Harness harness = bootstrappedHarness();
        PersistedExecutionStart started = start(harness, TOKEN, "start-completion-chain");
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(harness.state()),
                PersistenceFixtures.plan(),
                TOKEN,
                started.fencingToken(),
                PersistenceFixtures.workspaceSpec("recovery-completion-chain"));
        requireApplied(harness.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        started.fencingToken(),
                        "activation-completion-chain")));
        requireApplied(harness.completions().complete(
                completionRequest(harness, "completion-chain")));
        Plan completed = harness.state().plans.get(PersistenceFixtures.PLAN_ID);
        requireApplied(harness.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        completed,
                        harness.state().checkpoints
                                .get(PersistenceFixtures.PLAN_ID)
                                .checkpoint(),
                        4,
                        4,
                        TOKEN,
                        started.fencingToken(),
                        PersistenceFixtures.STEP_2,
                        "activation-after-completion-chain",
                        5)));

        assertAdvanced(harness.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void validActivationCompletionActivationInterruptionChainIsAdvanced() {
        Harness harness = bootstrappedHarness();
        PersistedExecutionStart started = start(harness, TOKEN, "start-interruption-chain");
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(harness.state()),
                PersistenceFixtures.plan(),
                TOKEN,
                started.fencingToken(),
                PersistenceFixtures.workspaceSpec("recovery-interruption-chain"));
        requireApplied(harness.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        started.fencingToken(),
                        "activation-before-interruption-chain")));
        requireApplied(harness.completions().complete(
                completionRequest(harness, "completion-before-interruption-chain")));
        Plan completed = harness.state().plans.get(PersistenceFixtures.PLAN_ID);
        requireApplied(harness.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        completed,
                        harness.state().checkpoints
                                .get(PersistenceFixtures.PLAN_ID)
                                .checkpoint(),
                        4,
                        4,
                        TOKEN,
                        started.fencingToken(),
                        PersistenceFixtures.STEP_2,
                        "activation-before-pause-chain",
                        5)));
        requireApplied(harness.interruptions().pause(
                pauseRequest(harness, "pause-recovery-chain")));

        assertAdvanced(harness.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void committedRecoveryAcceptsNoneReservedAndConfirmedContext() {
        Harness none = bootstrappedHarness();
        start(none, TOKEN, "start-context-none");
        assertTrue(none.recovery().inspect(PersistenceFixtures.PLAN_ID)
                .value().orElseThrow()
                instanceof PersistedExecutionStartCommitted);

        Harness reserved = bootstrappedHarness();
        PersistedExecutionStart reservedStart =
                start(reserved, TOKEN, "start-context-reserved");
        var reservedSpec = PersistenceFixtures.workspaceSpec(
                "recovery-reserved");
        requireApplied(
                new InMemoryPlanExecutionContextRepository(
                        reserved.state()).reserve(
                                PersistenceFixtures
                                        .contextReservationRequest(
                                                PersistenceFixtures.plan(),
                                                TOKEN,
                                                reservedStart.fencingToken(),
                                                reservedSpec)));
        assertTrue(reserved.recovery().inspect(PersistenceFixtures.PLAN_ID)
                .value().orElseThrow()
                instanceof PersistedExecutionStartCommitted);

        Harness confirmed = bootstrappedHarness();
        PersistedExecutionStart confirmedStart =
                start(confirmed, TOKEN, "start-context-confirmed");
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(
                        confirmed.state()),
                PersistenceFixtures.plan(),
                TOKEN,
                confirmedStart.fencingToken(),
                PersistenceFixtures.workspaceSpec(
                        "recovery-confirmed"));
        assertTrue(confirmed.recovery().inspect(
                        PersistenceFixtures.PLAN_ID)
                .value().orElseThrow()
                instanceof PersistedExecutionStartCommitted);
    }

    @Test
    void impossibleContextAndSuccessorCutsAreAlwaysPartial() {
        Harness reservedSuccessor = bootstrappedHarness();
        PersistedExecutionStart reservedSuccessorStart =
                start(
                        reservedSuccessor,
                        TOKEN,
                        "reserved-successor");
        WorkspaceMaterializationSpec reservedSpec =
                PersistenceFixtures.workspaceSpec(
                        "reserved-successor");
        PlanExecutionContextRepository reservedContexts =
                new InMemoryPlanExecutionContextRepository(
                        reservedSuccessor.state());
        PersistenceFixtures.confirmExecutionContext(
                reservedContexts,
                PersistenceFixtures.plan(),
                TOKEN,
                reservedSuccessorStart.fencingToken(),
                reservedSpec);
        requireApplied(reservedSuccessor.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        reservedSuccessorStart.fencingToken(),
                        "activation-reserved-successor")));
        reservedSuccessor.state()
                .planExecutionContextConfirmations
                .remove(PersistenceFixtures.PLAN_ID);
        assertPartial(reservedSuccessor.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness missingContextSuccessor = bootstrappedHarness();
        PersistedExecutionStart missingContextStart =
                start(
                        missingContextSuccessor,
                        TOKEN,
                        "missing-context-successor");
        WorkspaceMaterializationSpec missingContextSpec =
                PersistenceFixtures.workspaceSpec(
                        "missing-context-successor");
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(
                        missingContextSuccessor.state()),
                PersistenceFixtures.plan(),
                TOKEN,
                missingContextStart.fencingToken(),
                missingContextSpec);
        requireApplied(missingContextSuccessor.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        missingContextStart.fencingToken(),
                        "activation-missing-context-successor")));
        missingContextSuccessor.state()
                .planExecutionContextConfirmations
                .remove(PersistenceFixtures.PLAN_ID);
        missingContextSuccessor.state()
                .planExecutionContextReservations
                .remove(PersistenceFixtures.PLAN_ID);
        missingContextSuccessor.state().workspaceOwners
                .remove(missingContextSpec.workspaceId());
        assertPartial(missingContextSuccessor.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness sourceLess = harness();
        requireApplied(sourceLess.bootstraps().bootstrap(
                PersistenceFixtures.sourceLessTaskFrame(
                        PersistenceFixtures.TASK_ID,
                        "source-less context corruption"),
                PersistenceFixtures.plan(),
                PersistenceFixtures.initialCheckpoint(
                        PersistenceFixtures.plan())));
        PersistedExecutionStart sourceLessStart =
                start(sourceLess, TOKEN, "source-less-context");
        WorkspaceMaterializationSpec sourceLessSpec =
                PersistenceFixtures.workspaceSpec(
                        "source-less-context");
        PlanExecutionContextReservationRequest sourceLessRequest =
                PersistenceFixtures.contextReservationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        sourceLessStart.fencingToken(),
                        sourceLessSpec);
        PersistedPlanExecutionContextReserved sourceLessReserved =
                new PersistedPlanExecutionContextReserved(
                        PersistenceFixtures.PLAN_ID,
                        sourceLessSpec,
                        OWNER,
                        sourceLessStart.fencingToken());
        sourceLess.state().planExecutionContextReservations.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState
                        .PlanExecutionContextReservationMarker(
                                sourceLessRequest,
                                sourceLessReserved,
                                sourceLess.state()
                                        .executionMutationHeads
                                        .get(PersistenceFixtures.PLAN_ID)));
        sourceLess.state().workspaceOwners.put(
                sourceLessSpec.workspaceId(),
                new InMemoryState.WorkspaceOwner(
                        PersistenceFixtures.PLAN_ID,
                        sourceLessSpec));
        assertPartial(sourceLess.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness confirmationOnly = bootstrappedHarness();
        PersistedExecutionStart confirmationOnlyStart =
                start(
                        confirmationOnly,
                        TOKEN,
                        "confirmation-only");
        WorkspaceMaterializationSpec confirmationOnlySpec =
                PersistenceFixtures.workspaceSpec(
                        "confirmation-only-recovery");
        PersistedPlanExecutionContextReserved absentReservation =
                new PersistedPlanExecutionContextReserved(
                        PersistenceFixtures.PLAN_ID,
                        confirmationOnlySpec,
                        OWNER,
                        confirmationOnlyStart.fencingToken());
        PlanExecutionContextConfirmationRequest confirmationRequest =
                PersistenceFixtures.contextConfirmationRequest(
                        PersistenceFixtures.plan(),
                        TOKEN,
                        confirmationOnlyStart.fencingToken(),
                        confirmationOnlySpec);
        confirmationOnly.state()
                .planExecutionContextConfirmations.put(
                        PersistenceFixtures.PLAN_ID,
                        new InMemoryState
                                .PlanExecutionContextConfirmationMarker(
                                        confirmationRequest,
                                        new PersistedPlanExecutionContextConfirmed(
                                                absentReservation,
                                                OWNER,
                                                confirmationOnlyStart
                                                        .fencingToken(),
                                                confirmationRequest
                                                        .sourceManifestFingerprint())));
        assertPartial(confirmationOnly.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness wrongSpec = bootstrappedHarness();
        PersistedExecutionStart wrongSpecStart =
                start(wrongSpec, TOKEN, "wrong-spec");
        WorkspaceMaterializationSpec originalSpec =
                PersistenceFixtures.workspaceSpec(
                        "wrong-spec-original");
        PlanExecutionContextRepository wrongSpecContexts =
                new InMemoryPlanExecutionContextRepository(
                        wrongSpec.state());
        PersistenceFixtures.confirmExecutionContext(
                wrongSpecContexts,
                PersistenceFixtures.plan(),
                TOKEN,
                wrongSpecStart.fencingToken(),
                originalSpec);
        InMemoryState.PlanExecutionContextConfirmationMarker
                wrongSpecMarker =
                wrongSpec.state()
                        .planExecutionContextConfirmations
                        .get(PersistenceFixtures.PLAN_ID);
        wrongSpec.state().planExecutionContextConfirmations.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState
                        .PlanExecutionContextConfirmationMarker(
                                new PlanExecutionContextConfirmationRequest(
                                        PersistenceFixtures.PLAN_ID,
                                        TOKEN,
                                        wrongSpecStart.fencingToken(),
                                        PersistenceFixtures.workspaceSpec(
                                                "wrong-confirm-spec"),
                                        wrongSpecMarker.request()
                                                .sourceManifestFingerprint()),
                                wrongSpecMarker.result()));
        assertPartial(wrongSpec.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness wrongH0 = bootstrappedHarness();
        PersistedExecutionStart wrongH0Start =
                start(wrongH0, TOKEN, "wrong-h0");
        WorkspaceMaterializationSpec wrongH0Spec =
                PersistenceFixtures.workspaceSpec("wrong-h0");
        requireApplied(new InMemoryPlanExecutionContextRepository(
                wrongH0.state()).reserve(
                        PersistenceFixtures
                                .contextReservationRequest(
                                        PersistenceFixtures.plan(),
                                        TOKEN,
                                        wrongH0Start.fencingToken(),
                                        wrongH0Spec)));
        InMemoryState.PlanExecutionContextReservationMarker
                wrongH0Marker =
                wrongH0.state()
                        .planExecutionContextReservations
                        .get(PersistenceFixtures.PLAN_ID);
        InMemoryState.ExecutionMutationHead h0 =
                wrongH0Marker.frozenH0();
        wrongH0.state().planExecutionContextReservations.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState
                        .PlanExecutionContextReservationMarker(
                                wrongH0Marker.request(),
                                wrongH0Marker.result(),
                                new InMemoryState.ExecutionMutationHead(
                                        h0.revisionId(),
                                        h0.revisionNumber(),
                                        h0.checkpointVersion(),
                                        h0.eventHeadSequence(),
                                        new EventId(
                                                "wrong-context-h0"))));
        assertPartial(wrongH0.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void receiptPresenceControlsAdvancedClassificationWithoutReadingContent() {
        Harness missingCheckpointReceipt = bootstrappedHarness();
        start(missingCheckpointReceipt, TOKEN, "start-missing-checkpoint");
        missingCheckpointReceipt.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(3L, PersistenceFixtures.event("event-3", 3));
        missingCheckpointReceipt.state().eventsById.put(
                new EventId("event-3"),
                PersistenceFixtures.event("event-3", 3));
        missingCheckpointReceipt.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                new VersionedCheckpoint(
                        3,
                        progressedCheckpoint(
                                PersistenceFixtures.plan(),
                                3,
                                List.of(new ReceiptId("receipt-missing")))));
        assertPartial(missingCheckpointReceipt.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

    }

    @Test
    void eventIndexSequenceAndCursorCorruptionArePartial() {
        Harness missingIndex = bootstrappedHarness();
        PersistedExecutionStart missingIndexStart =
                start(missingIndex, TOKEN, "start-index");
        missingIndex.state().eventsById.remove(missingIndexStart.startEvent().id());
        assertPartial(missingIndex.recovery().inspect(PersistenceFixtures.PLAN_ID));

        Harness mismatchedStreamKey = bootstrappedHarness();
        start(mismatchedStreamKey, TOKEN, "start-stream");
        EventEnvelope event3 = PersistenceFixtures.event("event-3", 3);
        mismatchedStreamKey.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(2L, event3);
        mismatchedStreamKey.state().eventsById.put(event3.id(), event3);
        assertPartial(mismatchedStreamKey.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness indexConflict = bootstrappedHarness();
        start(indexConflict, TOKEN, "start-conflict");
        EventEnvelope extra = PersistenceFixtures.event("event-extra", 3);
        indexConflict.state().eventsById.put(extra.id(), extra);
        assertPartial(indexConflict.recovery().inspect(PersistenceFixtures.PLAN_ID));

        Harness addedEventMissingIndex = bootstrappedHarness();
        start(addedEventMissingIndex, TOKEN, "start-added-missing");
        EventEnvelope missingAddedIndex =
                PersistenceFixtures.event("event-added-missing", 3);
        addedEventMissingIndex.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(3L, missingAddedIndex);
        assertPartial(addedEventMissingIndex.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness wrongIndexKey = bootstrappedHarness();
        start(wrongIndexKey, TOKEN, "start-wrong-key");
        EventEnvelope wronglyIndexed =
                PersistenceFixtures.event("event-wrongly-indexed", 3);
        wrongIndexKey.state().eventStreams
                .get(PersistenceFixtures.PLAN_ID)
                .put(3L, wronglyIndexed);
        wrongIndexKey.state().eventsById.put(
                new EventId("wrong-index-key"), wronglyIndexed);
        assertPartial(wrongIndexKey.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness danglingCursor = bootstrappedHarness();
        start(danglingCursor, TOKEN, "start-cursor");
        danglingCursor.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                new VersionedCheckpoint(
                        3,
                        progressedCheckpoint(PersistenceFixtures.plan(), 2, List.of())));
        assertPartial(danglingCursor.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void markerCheckpointAndPlanRegressionArePartial() {
        Harness markerMismatch = bootstrappedHarness();
        PersistedExecutionStart original =
                start(markerMismatch, TOKEN, "start-marker");
        ExecutionStartRequest request = markerMismatch.state()
                .executionStarts.get(PersistenceFixtures.PLAN_ID).request();
        markerMismatch.state().executionStarts.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState.ExecutionStartMarker(
                        request,
                        new PersistedExecutionStart(
                                original.planId(),
                                original.leaseOwnerId(),
                                original.fencingToken() + 1,
                                original.startEvent(),
                                original.startedCheckpoint())));
        assertPartial(markerMismatch.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness checkpointRegression = bootstrappedHarness();
        start(checkpointRegression, TOKEN, "start-regression");
        checkpointRegression.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                checkpointRegression.state().planBootstraps
                        .get(PersistenceFixtures.PLAN_ID)
                        .initialCheckpoint());
        assertPartial(checkpointRegression.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness planRegression = bootstrappedHarness();
        requireApplied(planRegression.plans().appendRevision(
                PersistenceFixtures.PLAN_ID,
                1,
                PersistenceFixtures.revision2("revision-2", "before start")));
        Plan revisionTwoPlan = planRegression.state().plans.get(
                PersistenceFixtures.PLAN_ID);
        start(planRegression, TOKEN, "start-revision-2", revisionTwoPlan);
        planRegression.state().plans.put(
                PersistenceFixtures.PLAN_ID, PersistenceFixtures.plan());
        assertPartial(planRegression.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void markerRequestResultPlanEventAndCheckpointMismatchesArePartial() {
        Harness planMismatch = bootstrappedHarness();
        PersistedExecutionStart planOriginal =
                start(planMismatch, TOKEN, "start-plan-binding");
        InMemoryState.ExecutionStartMarker planMarker =
                planMismatch.state().executionStarts.get(
                        PersistenceFixtures.PLAN_ID);
        PlanId otherPlanId = new PlanId("other-plan");
        planMismatch.state().executionStarts.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState.ExecutionStartMarker(
                        new ExecutionStartRequest(
                                otherPlanId,
                                planMarker.request().leaseToken(),
                                planOriginal.fencingToken(),
                                planOriginal.startEvent(),
                                planOriginal.startedCheckpoint().checkpoint()),
                        new PersistedExecutionStart(
                                otherPlanId,
                                planOriginal.leaseOwnerId(),
                                planOriginal.fencingToken(),
                                planOriginal.startEvent(),
                                planOriginal.startedCheckpoint())));
        assertPartial(planMismatch.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness eventMismatch = bootstrappedHarness();
        PersistedExecutionStart eventOriginal =
                start(eventMismatch, TOKEN, "start-event-binding");
        InMemoryState.ExecutionStartMarker eventMarker =
                eventMismatch.state().executionStarts.get(
                        PersistenceFixtures.PLAN_ID);
        eventMismatch.state().executionStarts.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState.ExecutionStartMarker(
                        new ExecutionStartRequest(
                                PersistenceFixtures.PLAN_ID,
                                eventMarker.request().leaseToken(),
                                eventOriginal.fencingToken(),
                                PersistenceFixtures.event(
                                        "different-marker-event", 1),
                                eventOriginal.startedCheckpoint().checkpoint()),
                        eventOriginal));
        assertPartial(eventMismatch.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness checkpointMismatch = bootstrappedHarness();
        PersistedExecutionStart checkpointOriginal =
                start(checkpointMismatch, TOKEN, "start-checkpoint-binding");
        InMemoryState.ExecutionStartMarker checkpointMarker =
                checkpointMismatch.state().executionStarts.get(
                        PersistenceFixtures.PLAN_ID);
        Checkpoint differentCheckpoint = startedCheckpointForRevision(
                PersistenceFixtures.plan(),
                PersistenceFixtures.plan().latestRevision(),
                PersistenceFixtures.T0.plusSeconds(2));
        checkpointMismatch.state().executionStarts.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState.ExecutionStartMarker(
                        new ExecutionStartRequest(
                                PersistenceFixtures.PLAN_ID,
                                checkpointMarker.request().leaseToken(),
                                checkpointOriginal.fencingToken(),
                                checkpointOriginal.startEvent(),
                                differentCheckpoint),
                        checkpointOriginal));
        assertPartial(checkpointMismatch.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
    }

    @Test
    void markerRevisionAndTimeBeforeBootstrapRootArePartial() {
        Harness revisionRegression = harness();
        Plan planAtRevisionTwo = new Plan(
                PersistenceFixtures.PLAN_ID,
                PersistenceFixtures.TASK_ID,
                List.of(
                        PersistenceFixtures.revision1(),
                        PersistenceFixtures.revision2(
                                "revision-2", "bootstrap at revision two")));
        requireApplied(revisionRegression.bootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                planAtRevisionTwo,
                PersistenceFixtures.initialCheckpoint(planAtRevisionTwo)));
        PersistedExecutionStart canonical = start(
                revisionRegression,
                TOKEN,
                "start-revision-floor",
                planAtRevisionTwo);
        Checkpoint revisionOneStarted = startedCheckpointForRevision(
                planAtRevisionTwo,
                planAtRevisionTwo.revisions().get(0),
                PersistenceFixtures.T0.plusSeconds(1));
        replaceMarkerCheckpoint(
                revisionRegression, canonical, revisionOneStarted);
        assertPartial(revisionRegression.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));

        Harness timeRegression = bootstrappedHarness();
        PersistedExecutionStart timeCanonical =
                start(timeRegression, TOKEN, "start-time-floor");
        Checkpoint timeRegressed = startedCheckpointForRevision(
                PersistenceFixtures.plan(),
                PersistenceFixtures.plan().latestRevision(),
                PersistenceFixtures.T0.minusSeconds(1));
        PersistedExecutionStart regressedStart = replaceMarkerCheckpoint(
                timeRegression, timeCanonical, timeRegressed);
        assertPartial(timeRegression.recovery()
                .inspect(PersistenceFixtures.PLAN_ID));
        PersistedPlanBootstrap bootstrap =
                timeRegression.state().planBootstraps.get(
                        PersistenceFixtures.PLAN_ID);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedExecutionStartCommitted(
                        bootstrap,
                        PersistenceFixtures.plan(),
                        regressedStart));
    }

    @Test
    void inspectionNeverWritesStateOrClockHighWater() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        Harness harness = bootstrappedHarness(clock);
        start(harness, TOKEN, "start-state");
        Map<?, ?> plans = Map.copyOf(harness.state().plans);
        Map<?, ?> taskFrames = Map.copyOf(harness.state().taskFrames);
        Map<?, ?> checkpoints = Map.copyOf(harness.state().checkpoints);
        Map<?, ?> events = Map.copyOf(harness.state().eventsById);
        Map<?, ?> eventStreams = eventStreamsSnapshot(harness.state());
        Map<?, ?> receipts = Map.copyOf(harness.state().receipts);
        Map<?, ?> bootstraps = Map.copyOf(harness.state().planBootstraps);
        Map<?, ?> starts = Map.copyOf(harness.state().executionStarts);
        Map<?, ?> leases = Map.copyOf(harness.state().leases);
        Map<?, ?> fences = Map.copyOf(harness.state().fencingTokens);
        Set<?> usedLeaseTokens = Set.copyOf(harness.state().usedLeaseTokens);
        Instant highWater = harness.state().leaseTimeHighWater;
        int observations = clock.observationCount();

        for (int index = 0; index < 20; index++) {
            assertEquals(PersistenceOutcome.FOUND, harness.recovery()
                    .inspect(PersistenceFixtures.PLAN_ID)
                    .outcome());
        }

        assertEquals(plans, harness.state().plans);
        assertEquals(taskFrames, harness.state().taskFrames);
        assertEquals(checkpoints, harness.state().checkpoints);
        assertEquals(events, harness.state().eventsById);
        assertEquals(eventStreams, eventStreamsSnapshot(harness.state()));
        assertEquals(receipts, harness.state().receipts);
        assertEquals(bootstraps, harness.state().planBootstraps);
        assertEquals(starts, harness.state().executionStarts);
        assertEquals(leases, harness.state().leases);
        assertEquals(fences, harness.state().fencingTokens);
        assertEquals(usedLeaseTokens, harness.state().usedLeaseTokens);
        assertEquals(highWater, harness.state().leaseTimeHighWater);
        assertEquals(observations, clock.observationCount());
    }

    @Test
    void publicSnapshotsRejectAuthorityDivergenceAndAdvancedCommittedShape() {
        Plan plan = PersistenceFixtures.plan();
        Checkpoint wrongRoot = new Checkpoint(
                PersistenceFixtures.TASK_ID,
                new PlanId("other-plan"),
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                Map.of(
                        PersistenceFixtures.STEP_1,
                        StepExecutionState.NOT_STARTED,
                        PersistenceFixtures.STEP_2,
                        StepExecutionState.NOT_STARTED),
                List.of(),
                PersistenceFixtures.T0);
        PersistedPlanBootstrap divergent = new PersistedPlanBootstrap(
                PersistenceFixtures.taskFrame(),
                plan,
                new VersionedCheckpoint(1, wrongRoot));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedExecutionStartReady(divergent, plan));

        Harness harness = bootstrappedHarness();
        PersistedExecutionStart started =
                start(harness, TOKEN, "start-public");
        Plan advanced = new Plan(
                PersistenceFixtures.PLAN_ID,
                PersistenceFixtures.TASK_ID,
                List.of(
                        PersistenceFixtures.revision1(),
                        PersistenceFixtures.revision2(
                                "revision-2", "advanced")));
        PersistedPlanBootstrap bootstrap =
                harness.state().planBootstraps.get(PersistenceFixtures.PLAN_ID);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedExecutionStartCommitted(
                        bootstrap, advanced, started));
    }

    private static Checkpoint progressedCheckpoint(
            Plan plan,
            long sequence,
            List<ReceiptId> receipts) {
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>();
        plan.latestRevision().steps().forEach(step ->
                states.put(step.id(), StepExecutionState.NOT_STARTED));
        states.put(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE);
        return new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                sequence,
                PlanExecutionState.ACTIVE,
                states,
                receipts,
                PersistenceFixtures.T0.plusSeconds(10));
    }

    private static Checkpoint startedCheckpointForRevision(
            Plan plan,
            PlanRevision revision,
            Instant createdAt) {
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>();
        revision.steps().forEach(step ->
                states.put(step.id(), StepExecutionState.NOT_STARTED));
        return new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                revision.id(),
                revision.number(),
                1,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                createdAt);
    }

    private static PersistedExecutionStart replaceMarkerCheckpoint(
            Harness harness,
            PersistedExecutionStart original,
            Checkpoint replacement) {
        InMemoryState.ExecutionStartMarker marker =
                harness.state().executionStarts.get(PersistenceFixtures.PLAN_ID);
        ExecutionStartRequest replacementRequest = new ExecutionStartRequest(
                original.planId(),
                marker.request().leaseToken(),
                original.fencingToken(),
                original.startEvent(),
                replacement);
        PersistedExecutionStart replacementResult =
                new PersistedExecutionStart(
                        original.planId(),
                        original.leaseOwnerId(),
                        original.fencingToken(),
                        original.startEvent(),
                        new VersionedCheckpoint(2, replacement));
        harness.state().executionStarts.put(
                PersistenceFixtures.PLAN_ID,
                new InMemoryState.ExecutionStartMarker(
                        replacementRequest, replacementResult));
        harness.state().checkpoints.put(
                PersistenceFixtures.PLAN_ID,
                replacementResult.startedCheckpoint());
        return replacementResult;
    }

    private static Map<PlanId, List<EventEnvelope>> eventStreamsSnapshot(
            InMemoryState state) {
        Map<PlanId, List<EventEnvelope>> snapshot = new LinkedHashMap<>();
        state.eventStreams.forEach((planId, events) ->
                snapshot.put(
                        planId,
                        events == null
                                ? null
                                : List.copyOf(events.values())));
        return snapshot;
    }

    private static PlanRevision revisionWithFact(ReceiptId receiptId) {
        PlanRevision previous = PersistenceFixtures.revision1();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "outcome-hash",
                PersistenceFixtures.T0.plusSeconds(5),
                List.of(receiptId));
        return new PlanRevision(
                new PlanRevisionId("revision-2"),
                PersistenceFixtures.TASK_ID,
                2,
                Optional.of(previous.id()),
                "record completion",
                PersistenceFixtures.T0.plusSeconds(5),
                previous.steps(),
                Map.of(PersistenceFixtures.STEP_1, fact));
    }

    private static StepCompletionRequest completionRequest(
            Harness harness,
            String suffix) {
        Plan plan = harness.state().plans.get(PersistenceFixtures.PLAN_ID);
        Checkpoint source = harness.state().checkpoints
                .get(PersistenceFixtures.PLAN_ID)
                .checkpoint();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "completion-" + suffix,
                source.createdAt().plusSeconds(1),
                List.of());
        PlanRevision completedRevision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                plan.taskFrameId(),
                2,
                Optional.of(plan.latestRevision().id()),
                "complete first step",
                source.createdAt().plusSeconds(1),
                plan.latestRevision().steps(),
                Map.of(PersistenceFixtures.STEP_1, fact));
        Checkpoint completedCheckpoint = new Checkpoint(
                source.taskFrameId(),
                source.planId(),
                completedRevision.id(),
                completedRevision.number(),
                4,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.SUCCEEDED,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                List.of(),
                source.createdAt().plusSeconds(1));
        return new StepCompletionRequest(
                plan.id(),
                TOKEN,
                1,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                3,
                3,
                PersistenceFixtures.STEP_1,
                fact,
                PersistenceFixtures.event(
                        "event-" + suffix,
                        plan.taskFrameId(),
                        plan.id(),
                        4),
                completedRevision,
                completedCheckpoint);
    }

    private static StepPauseRequest pauseRequest(
            Harness harness,
            String eventId) {
        Plan plan = harness.state().plans.get(PersistenceFixtures.PLAN_ID);
        VersionedCheckpoint source = harness.state().checkpoints
                .get(PersistenceFixtures.PLAN_ID);
        Checkpoint current = source.checkpoint();
        long eventSequence = current.lastEventSequence() + 1;
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(current.stepStates());
        states.put(PersistenceFixtures.STEP_2, StepExecutionState.PAUSED);
        Checkpoint paused = new Checkpoint(
                current.taskFrameId(),
                current.planId(),
                current.revisionId(),
                current.revisionNumber(),
                eventSequence,
                PlanExecutionState.PAUSED,
                states,
                current.receiptReferences(),
                current.createdAt().plusSeconds(1));
        return new StepPauseRequest(
                plan.id(),
                TOKEN,
                1,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                source.version(),
                current.lastEventSequence(),
                PersistenceFixtures.STEP_2,
                PersistenceFixtures.event(
                        eventId,
                        plan.taskFrameId(),
                        plan.id(),
                        eventSequence),
                paused);
    }

    private static PersistedExecutionStart start(
            Harness harness,
            String leaseToken,
            String eventId) {
        return start(
                harness,
                leaseToken,
                eventId,
                harness.state().plans.get(PersistenceFixtures.PLAN_ID));
    }

    private static PersistedExecutionStart start(
            Harness harness,
            String leaseToken,
            String eventId,
            Plan plan) {
        LeaseRecord lease = requireApplied(harness.leases().acquire(
                plan.id(),
                OWNER,
                leaseToken,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        return requireApplied(harness.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        plan,
                        leaseToken,
                        lease.fencingToken(),
                        eventId)));
    }

    private static Harness bootstrappedHarness() {
        return bootstrappedHarness(
                new PersistenceFixtures.MutableCountingClock(
                        PersistenceFixtures.T0));
    }

    private static Harness bootstrappedHarness(java.time.Clock clock) {
        Harness harness = harness(clock);
        requireApplied(harness.bootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                PersistenceFixtures.plan(),
                PersistenceFixtures.initialCheckpoint(
                        PersistenceFixtures.plan())));
        return harness;
    }

    private static Harness harness() {
        return harness(new PersistenceFixtures.MutableCountingClock(
                PersistenceFixtures.T0));
    }

    private static Harness harness(java.time.Clock clock) {
        InMemoryState state = new InMemoryState(clock);
        return new Harness(
                state,
                new InMemoryTaskFrameRepository(state),
                new InMemoryPlanRepository(state),
                new InMemoryEventRepository(state),
                new InMemoryReceiptRepository(state),
                new InMemoryCheckpointRepository(state),
                new InMemoryPlanBootstrapRepository(state),
                new InMemoryLeaseRepository(state),
                new InMemoryExecutionStartRepository(state),
                new InMemoryStepActivationRepository(state),
                new InMemoryStepCompletionRepository(state),
                new InMemoryStepInterruptionRepository(state),
                new InMemoryExecutionStartRecoveryRepository(state));
    }

    private static void assertOpaque(String text, String secret) {
        assertFalse(text.contains(secret), text);
        assertFalse(text.contains("ExecutionStartRequest"), text);
        assertFalse(text.contains("LeaseRecord"), text);
    }

    private static void assertPartial(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        assertFailure(
                result,
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    private static void assertAdvanced(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        assertFailure(
                result,
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
        assertTrue(result.value().isEmpty());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private record Harness(
            InMemoryState state,
            TaskFrameRepository taskFrames,
            PlanRepository plans,
            EventRepository events,
            ReceiptRepository receipts,
            CheckpointRepository checkpoints,
            PlanBootstrapRepository bootstraps,
            LeaseRepository leases,
            ExecutionStartRepository executionStarts,
            StepActivationRepository activations,
            StepCompletionRepository completions,
            StepInterruptionRepository interruptions,
            ExecutionStartRecoveryRepository recovery) {
    }
}
