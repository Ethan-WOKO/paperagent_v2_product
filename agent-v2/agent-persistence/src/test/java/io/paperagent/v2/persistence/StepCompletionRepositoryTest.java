package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepCompletionRepositoryTest {
    private static final String OWNER = "completion-owner";
    private static final String TOKEN = "completion-token";

    @Test
    void effectFreeActiveStepCompletesWithExactlyTheFrozenWriteSet() {
        Harness harness = activated("effect-free");
        StepCompletionRequest request = completionRequest(
                harness, PersistenceFixtures.STEP_1, "effect-free", List.of());
        AuthoritySnapshot before = snapshot(harness.state());

        PersistedStepCompletion completed = requireApplied(
                harness.completions().complete(request));

        assertEquals(request.planId(), completed.planId());
        assertEquals(request.stepId(), completed.stepId());
        assertEquals(OWNER, completed.leaseOwnerId());
        assertEquals(1, completed.fencingToken());
        assertEquals(request.completionEvent(), completed.completionEvent());
        assertEquals(request.completedRevision(), completed.completedRevision());
        assertEquals(request.completedCheckpoint(),
                completed.completedCheckpoint().checkpoint());
        assertEquals(4, completed.completedCheckpoint().version());
        assertEquals(2, harness.state().plans.get(harness.plan().id()).revisions().size());
        assertEquals(3, harness.state().eventStreams.get(harness.plan().id()).size());
        assertEquals(4, harness.state().checkpoints.get(harness.plan().id()).version());
        assertEquals(1, harness.state().stepCompletions.get(harness.plan().id()).size());
        assertEquals(2, harness.state().executionMutationLinks.get(harness.plan().id()).size());
        assertEquals(completed.completedCheckpoint().version(), harness.state()
                .executionMutationHeads.get(harness.plan().id()).checkpointVersion());
        assertEquals(StepExecutionState.SUCCEEDED, harness.state().checkpoints
                .get(harness.plan().id()).checkpoint().stepStates()
                .get(PersistenceFixtures.STEP_1));
        assertEquals(PlanExecutionState.ACTIVE, harness.state().checkpoints
                .get(harness.plan().id()).checkpoint().planState());
        assertUnchangedExceptCompletion(before, harness.state());
        assertAdvanced(harness.recovery().inspect(harness.plan().id()));
    }

    @Test
    void effectBackedCompletionRequiresEveryOwnedReceiptInCanonicalToolCallOrder() {
        Harness missing = activated("missing-effect");
        ToolCallId pending = durableIntent(missing, "pending", PersistenceFixtures.STEP_1);
        StepCompletionRequest missingResult = completionRequest(
                missing, PersistenceFixtures.STEP_1, "missing-effect", List.of());
        assertFailure(missing.completions().complete(missingResult),
                PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                "stepCompletion.effectOutcomes");
        assertTrue(missing.state().stepCompletions.get(missing.plan().id()).isEmpty());
        assertTrue(missing.state().effectResults.isEmpty());

        Harness harness = activated("canonical-effects");
        ToolCallId zulu = durableIntent(harness, "zulu", PersistenceFixtures.STEP_1);
        ToolCallId alpha = durableIntent(harness, "alpha", PersistenceFixtures.STEP_1);
        ReceiptId zuluReceipt = ownedResult(harness, zulu, "zulu");
        ReceiptId alphaReceipt = ownedResult(harness, alpha, "alpha");
        assertEquals(new ToolCallId("effect-pending"), pending);
        StepCompletionRequest wrongOrder = completionRequest(
                harness,
                PersistenceFixtures.STEP_1,
                "wrong-order",
                List.of(zuluReceipt, alphaReceipt));
        assertFailure(harness.completions().complete(wrongOrder),
                PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                "stepCompletion.effectOutcomes");

        StepCompletionRequest request = completionRequest(
                harness,
                PersistenceFixtures.STEP_1,
                "canonical-effects",
                List.of(alphaReceipt, zuluReceipt));
        PersistedStepCompletion completed = requireApplied(
                harness.completions().complete(request));

        assertEquals(List.of(alphaReceipt, zuluReceipt), completed.completedRevision()
                .completedFacts().get(PersistenceFixtures.STEP_1)
                .receiptReferences());
        assertEquals(List.of(alphaReceipt, zuluReceipt), completed.completedCheckpoint()
                .checkpoint().receiptReferences());
        assertEquals(2, harness.state().effectIntents.size());
        assertEquals(2, harness.state().effectResults.size());
        assertEquals(2, harness.state().receipts.size());
    }

    @Test
    void exactReplaySurvivesLeaseTakeoverAndLaterActivationWithoutClockAccess() {
        Harness harness = activated("replay");
        StepCompletionRequest request = completionRequest(
                harness, PersistenceFixtures.STEP_1, "replay", List.of());
        PersistedStepCompletion original = requireApplied(
                harness.completions().complete(request));
        harness.clock().set(PersistenceFixtures.T0.plusSeconds(60));
        LeaseRecord takeover = requireApplied(harness.leases().acquire(
                harness.plan().id(), "new-owner", "new-token",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));
        assertEquals(2, takeover.fencingToken());
        requireApplied(harness.activations().activate(PersistenceFixtures.stepActivationRequest(
                harness.state().plans.get(harness.plan().id()),
                harness.state().checkpoints.get(harness.plan().id()).checkpoint(),
                4,
                4,
                "new-token",
                2,
                PersistenceFixtures.STEP_2,
                "activation-after-completion",
                5)));
        harness.state().leases.clear();
        harness.state().checkpoints.clear();
        harness.state().effectResults.clear();
        harness.clock().failOnObservation();

        assertEquals(original, requireReplayed(harness.completions().complete(request)));
    }

    @Test
    void completionEnablesTheDependentNextStepAndFinalStepSucceedsThePlan() {
        Harness harness = activated("final-plan");
        requireApplied(harness.completions().complete(completionRequest(
                harness, PersistenceFixtures.STEP_1, "first-completion", List.of())));
        Plan afterFirst = harness.state().plans.get(harness.plan().id());
        requireApplied(harness.activations().activate(PersistenceFixtures.stepActivationRequest(
                afterFirst,
                harness.state().checkpoints.get(harness.plan().id()).checkpoint(),
                4,
                4,
                TOKEN,
                1,
                PersistenceFixtures.STEP_2,
                "activate-second",
                5)));

        PersistedStepCompletion finalCompletion = requireApplied(
                harness.completions().complete(completionRequest(
                        harness, PersistenceFixtures.STEP_2, "final-completion", List.of())));

        assertEquals(PlanExecutionState.SUCCEEDED, finalCompletion.completedCheckpoint()
                .checkpoint().planState());
        assertTrue(finalCompletion.completedCheckpoint().checkpoint().stepStates().values()
                .stream().allMatch(state -> state == StepExecutionState.SUCCEEDED));
        assertEquals(3, finalCompletion.completedRevision().number());
        assertEquals(3, harness.state().plans.get(harness.plan().id()).revisions().size());
    }

    @Test
    void alteredCurrentCompletionRevisionFailsClosedBeforeDependentActivation() {
        Harness harness = activated("altered-current-revision");
        requireApplied(harness.completions().complete(completionRequest(
                harness, PersistenceFixtures.STEP_1,
                "altered-current-revision", List.of())));
        Plan current = harness.state().plans.get(harness.plan().id());
        PlanRevision completed = current.latestRevision();
        PlanRevision altered = new PlanRevision(
                completed.id(),
                completed.taskFrameId(),
                completed.number(),
                completed.parentRevisionId(),
                "altered completion revision",
                completed.createdAt(),
                completed.steps(),
                completed.completedFacts());
        List<PlanRevision> revisions = new ArrayList<>(current.revisions());
        revisions.set(revisions.size() - 1, altered);
        Plan alteredPlan = new Plan(
                current.id(), current.taskFrameId(), revisions);
        harness.state().plans.put(harness.plan().id(), alteredPlan);
        AuthoritySnapshot before = snapshot(harness.state());

        assertFailure(harness.activations().activate(
                        PersistenceFixtures.stepActivationRequest(
                                alteredPlan,
                                harness.state().checkpoints.get(harness.plan().id())
                                        .checkpoint(),
                                4,
                                4,
                                TOKEN,
                                1,
                                PersistenceFixtures.STEP_2,
                                "activation-after-altered-revision",
                                5)),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");

        assertUnchangedExceptCompletion(before, harness.state());
        assertEquals(alteredPlan, harness.state().plans.get(harness.plan().id()));
    }

    @Test
    void staleInactiveAndMalformedCandidatesFailWithoutAnyBusinessWrite() {
        Harness harness = activated("failures");
        AuthoritySnapshot before = snapshot(harness.state());
        StepCompletionRequest request = completionRequest(
                harness, PersistenceFixtures.STEP_1, "failures", List.of());

        StepCompletionRequest stale = new StepCompletionRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion() + 1,
                request.expectedEventHeadSequence(), request.stepId(),
                request.completionFact(), request.completionEvent(),
                request.completedRevision(), request.completedCheckpoint());
        assertFailure(harness.completions().complete(stale),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");

        PlanRevision malformedRevision = new PlanRevision(
                request.completedRevision().id(),
                request.completedRevision().taskFrameId(),
                request.completedRevision().number(),
                request.completedRevision().parentRevisionId(),
                request.completedRevision().reason(),
                request.completedRevision().createdAt(),
                List.of(PersistenceFixtures.step(PersistenceFixtures.STEP_1, java.util.Set.of())),
                request.completedRevision().completedFacts());
        StepCompletionRequest malformed = new StepCompletionRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(), request.expectedEventHeadSequence(),
                request.stepId(), request.completionFact(), request.completionEvent(),
                malformedRevision, request.completedCheckpoint());
        assertFailure(harness.completions().complete(malformed),
                PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                "request.completedRevision");
        assertUnchangedExceptCompletion(before, harness.state());
        assertTrue(harness.state().stepCompletions.get(harness.plan().id()).isEmpty());
    }

    @Test
    void corruptMarkersAndEvidenceFailClosedWithoutCompletionWrites() {
        Harness markerHarness = activated("corrupt-marker");
        StepCompletionRequest markerRequest = completionRequest(
                markerHarness, PersistenceFixtures.STEP_1, "corrupt-marker", List.of());
        requireApplied(markerHarness.completions().complete(markerRequest));
        InMemoryState.StepCompletionMarker marker = markerHarness.state().stepCompletions
                .get(markerHarness.plan().id())
                .get(markerRequest.completionEvent().id());
        markerHarness.state().stepCompletions.get(markerHarness.plan().id()).put(
                markerRequest.completionEvent().id(),
                new InMemoryState.StepCompletionMarker(
                        marker.request(), marker.result(), null));
        markerHarness.clock().failOnObservation();
        assertFailure(markerHarness.completions().complete(markerRequest),
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                "stepCompletion");

        Harness tornReceipt = activated("torn-receipt");
        ToolCallId toolCallId = durableIntent(
                tornReceipt, "torn-receipt", PersistenceFixtures.STEP_1);
        ReceiptId receiptId = ownedResult(tornReceipt, toolCallId, "torn-receipt");
        tornReceipt.state().receipts.remove(receiptId);
        AuthoritySnapshot before = snapshot(tornReceipt.state());
        assertFailure(tornReceipt.completions().complete(completionRequest(
                        tornReceipt,
                        PersistenceFixtures.STEP_1,
                        "torn-receipt",
                        List.of(receiptId))),
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                "stepCompletion");
        assertUnchangedExceptCompletion(before, tornReceipt.state());

        Harness detachedIntent = activated("detached-intent");
        ToolCallId detachedToolCall = new ToolCallId("effect-detached-intent");
        EffectIntent intent = new EffectIntent(
                detachedToolCall,
                detachedIntent.plan().id(),
                PersistenceFixtures.STEP_1,
                "workspace.edit",
                new ObjectValue(Map.of("input", new TextValue("detached"))));
        EventId detachedActivationId = new EventId("activation-not-in-chain");
        EffectIntentRequest intentRequest = new EffectIntentRequest(
                intent, TOKEN, 1, detachedActivationId);
        detachedIntent.state().effectIntents.put(detachedToolCall,
                new InMemoryState.EffectIntentMarker(
                        intentRequest,
                        new PersistedEffectIntent(intent, OWNER, 1, detachedActivationId),
                        OWNER));
        var detachedReceipt = PersistenceFixtures.receipt(
                "receipt-detached-intent", detachedToolCall.value());
        detachedIntent.state().receipts.put(detachedReceipt.id(), detachedReceipt);
        detachedIntent.state().effectResults.put(detachedToolCall,
                new InMemoryState.EffectResultMarker(
                        new EffectResultRequest(detachedReceipt, TOKEN, 1),
                        new PersistedEffectResult(detachedReceipt, OWNER, 1),
                        OWNER));
        assertFailure(detachedIntent.completions().complete(completionRequest(
                        detachedIntent,
                        PersistenceFixtures.STEP_1,
                        "detached-intent",
                        List.of(detachedReceipt.id()))),
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                "stepCompletion");
    }

    @Test
    void eventConflictsAndInvalidCompletionCheckpointRejectWithoutMutation() {
        Harness eventConflict = activated("event-conflict");
        StepCompletionRequest eventRequest = completionRequest(
                eventConflict, PersistenceFixtures.STEP_1, "event-conflict", List.of());
        eventConflict.state().eventsById.put(eventRequest.completionEvent().id(),
                PersistenceFixtures.event(
                        eventRequest.completionEvent().id().value(),
                        eventConflict.plan().taskFrameId(),
                        new PlanId("other-plan"),
                        1));
        assertFailure(eventConflict.completions().complete(eventRequest),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.completionEvent.id");

        Harness invalidCheckpoint = activated("invalid-checkpoint");
        StepCompletionRequest request = completionRequest(
                invalidCheckpoint,
                PersistenceFixtures.STEP_1,
                "invalid-checkpoint",
                List.of());
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                request.completedCheckpoint().stepStates());
        states.put(PersistenceFixtures.STEP_2, StepExecutionState.ACTIVE);
        Checkpoint malformedCheckpoint = new Checkpoint(
                request.completedCheckpoint().taskFrameId(),
                request.completedCheckpoint().planId(),
                request.completedCheckpoint().revisionId(),
                request.completedCheckpoint().revisionNumber(),
                request.completedCheckpoint().lastEventSequence(),
                request.completedCheckpoint().planState(),
                states,
                request.completedCheckpoint().receiptReferences(),
                request.completedCheckpoint().createdAt());
        StepCompletionRequest malformed = new StepCompletionRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(), request.expectedEventHeadSequence(),
                request.stepId(), request.completionFact(), request.completionEvent(),
                request.completedRevision(), malformedCheckpoint);
        assertFailure(invalidCheckpoint.completions().complete(malformed),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.completedCheckpoint");
    }

    private static Harness activated(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(), plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(), OWNER, TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state), plan, TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("completion-" + suffix));
        EventId activation = new EventId("activation-" + suffix);
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan, TOKEN, lease.fencingToken(), activation.value())));
        return new Harness(
                state,
                clock,
                plan,
                leases,
                activation,
                new InMemoryStepActivationRepository(state),
                new InMemoryEffectIntentRepository(state),
                new InMemoryEffectOutcomeRepository(state),
                new InMemoryStepCompletionRepository(state),
                new InMemoryExecutionStartRecoveryRepository(state));
    }

    private static ToolCallId durableIntent(
            Harness harness,
            String suffix,
            PlanStepId stepId) {
        ToolCallId call = new ToolCallId("effect-" + suffix);
        requireApplied(harness.intents().persist(new EffectIntentRequest(
                new EffectIntent(
                        call,
                        harness.plan().id(),
                        stepId,
                        "workspace.edit",
                        new ObjectValue(Map.of(
                                "input", new TextValue("argument-" + suffix)))),
                TOKEN,
                1,
                harness.activationEventId())));
        return call;
    }

    private static ReceiptId ownedResult(
            Harness harness,
            ToolCallId toolCallId,
            String suffix) {
        var receipt = PersistenceFixtures.receipt("receipt-" + suffix, toolCallId.value());
        requireApplied(harness.outcomes().recordResult(new EffectResultRequest(
                receipt, TOKEN, 1)));
        return receipt.id();
    }

    private static StepCompletionRequest completionRequest(
            Harness harness,
            PlanStepId stepId,
            String suffix,
            List<ReceiptId> receiptIds) {
        Plan plan = harness.state().plans.get(harness.plan().id());
        VersionedCheckpoint source = harness.state().checkpoints.get(plan.id());
        Checkpoint current = source.checkpoint();
        CompletionFact fact = new CompletionFact(
                stepId,
                "outcome-" + suffix,
                current.createdAt().plusSeconds(1),
                receiptIds);
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>(
                plan.latestRevision().completedFacts());
        facts.put(stepId, fact);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-complete-" + suffix),
                plan.taskFrameId(),
                plan.latestRevision().number() + 1,
                Optional.of(plan.latestRevision().id()),
                "complete " + stepId.value(),
                current.createdAt().plusSeconds(1),
                plan.latestRevision().steps(),
                facts);
        long eventSequence = current.lastEventSequence() + 1;
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                current.stepStates());
        states.put(stepId, StepExecutionState.SUCCEEDED);
        List<ReceiptId> checkpointReceipts = new ArrayList<>(
                current.receiptReferences());
        checkpointReceipts.addAll(receiptIds);
        boolean allSucceeded = states.values().stream().allMatch(
                state -> state == StepExecutionState.SUCCEEDED);
        Checkpoint target = new Checkpoint(
                current.taskFrameId(),
                current.planId(),
                revision.id(),
                revision.number(),
                eventSequence,
                allSucceeded ? PlanExecutionState.SUCCEEDED : PlanExecutionState.ACTIVE,
                states,
                checkpointReceipts,
                current.createdAt().plusSeconds(1));
        return new StepCompletionRequest(
                plan.id(),
                TOKEN,
                1,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                source.version(),
                current.lastEventSequence(),
                stepId,
                fact,
                PersistenceFixtures.event(
                        "completion-" + suffix,
                        plan.taskFrameId(),
                        plan.id(),
                        eventSequence),
                revision,
                target);
    }

    private static AuthoritySnapshot snapshot(InMemoryState state) {
        return new AuthoritySnapshot(
                state.taskFrames.size(),
                state.planBootstraps.size(),
                state.executionStarts.size(),
                state.stepActivations.size(),
                state.effectIntents.size(),
                state.effectProgresses.size(),
                state.effectResults.size(),
                state.receipts.size(),
                state.planExecutionContextReservations.size(),
                state.planExecutionContextConfirmations.size(),
                state.workspaceOwners.size(),
                state.leases.size(),
                state.idempotency.size());
    }

    private static void assertUnchangedExceptCompletion(
            AuthoritySnapshot expected,
            InMemoryState state) {
        assertEquals(expected, snapshot(state));
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static <T> T requireReplayed(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.REPLAYED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private static void assertAdvanced(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        assertFailure(result,
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");
    }

    private record AuthoritySnapshot(
            int taskFrames,
            int bootstraps,
            int starts,
            int activations,
            int effectIntents,
            int effectProgresses,
            int effectResults,
            int receipts,
            int reservations,
            int confirmations,
            int workspaces,
            int leases,
            int idempotency) {
    }

    private record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            InMemoryLeaseRepository leases,
            EventId activationEventId,
            StepActivationRepository activations,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            StepCompletionRepository completions,
            ExecutionStartRecoveryRepository recovery) {
    }
}
