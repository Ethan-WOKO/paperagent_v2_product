package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanReplanRepositoryTest {
    private static final String OWNER = "replan-owner";
    private static final String TOKEN = "replan-token";

    @Test
    void safeBoundaryReplanAtomicallyAppendsOnlyItsFrozenWriteSet() {
        Harness harness = started("initial");
        PlanReplanRequest request = replanRequest(
                harness, "initial", currentPlan(harness), List.of(
                        PersistenceFixtures.step(PersistenceFixtures.STEP_1, Set.of()),
                        PersistenceFixtures.step(
                                PersistenceFixtures.STEP_2,
                                Set.of(PersistenceFixtures.STEP_1))));
        Snapshot before = snapshot(harness.state());

        PersistedPlanReplan result = requireApplied(harness.replans().replan(request));

        assertEquals(request.planId(), result.planId());
        assertEquals(OWNER, result.leaseOwnerId());
        assertEquals(1, result.fencingToken());
        assertEquals(request.replanEvent(), result.replanEvent());
        assertEquals(request.replannedRevision(), result.replannedRevision());
        assertEquals(request.replannedCheckpoint(),
                result.replannedCheckpoint().checkpoint());
        assertEquals(3, result.replannedCheckpoint().version());
        assertEquals(2, harness.state().plans.get(harness.plan().id()).revisions().size());
        assertEquals(2, harness.state().eventStreams.get(harness.plan().id()).size());
        assertEquals(3, harness.state().checkpoints.get(harness.plan().id()).version());
        assertEquals(1, harness.state().planReplans.get(harness.plan().id()).size());
        assertEquals(1, harness.state().executionMutationLinks.get(harness.plan().id()).size());
        assertEquals(0, harness.state().stepActivations.get(harness.plan().id()).size());
        assertEquals(0, harness.state().stepCompletions.get(harness.plan().id()).size());
        assertEquals(PlanExecutionState.ACTIVE, result.replannedCheckpoint()
                .checkpoint().planState());
        assertTrue(result.replannedCheckpoint().checkpoint().stepStates().values()
                .stream().allMatch(state -> state == StepExecutionState.NOT_STARTED));
        assertUnchangedExceptReplan(before, harness.state());
        assertAdvanced(harness.recovery().inspect(harness.plan().id()));
    }

    @Test
    void replanPreservesCompletedFactsAndDefinitionsWhileChangingFutureSteps() {
        Harness harness = started("completed");
        completeFirstStep(harness, "completed");
        Plan source = currentPlan(harness);
        PlanStep changedFuture = new PlanStep(
                PersistenceFixtures.STEP_2,
                "Perform changed future work",
                "Verify changed future work",
                Set.of(PersistenceFixtures.STEP_1),
                List.of("future result is verified"),
                PersistenceFixtures.step(PersistenceFixtures.STEP_2,
                        Set.of(PersistenceFixtures.STEP_1)).executionHints());
        PlanStepId addedId = new PlanStepId("step-3");
        PlanReplanRequest request = replanRequest(
                harness,
                "completed",
                source,
                List.of(
                        source.latestRevision().steps().get(0),
                        changedFuture,
                        PersistenceFixtures.step(addedId, Set.of(PersistenceFixtures.STEP_2))));

        PersistedPlanReplan result = requireApplied(harness.replans().replan(request));

        assertEquals(source.latestRevision().completedFacts(),
                result.replannedRevision().completedFacts());
        assertEquals(source.latestRevision().steps().get(0),
                result.replannedRevision().steps().get(0));
        assertEquals(StepExecutionState.SUCCEEDED, result.replannedCheckpoint()
                .checkpoint().stepStates().get(PersistenceFixtures.STEP_1));
        assertEquals(StepExecutionState.NOT_STARTED, result.replannedCheckpoint()
                .checkpoint().stepStates().get(PersistenceFixtures.STEP_2));
        assertEquals(StepExecutionState.NOT_STARTED, result.replannedCheckpoint()
                .checkpoint().stepStates().get(addedId));
        assertEquals(source.latestRevision().completedFacts(), harness.state()
                .plans.get(harness.plan().id()).latestRevision().completedFacts());
        assertAdvanced(harness.recovery().inspect(harness.plan().id()));
    }

    @Test
    void exactReplaySurvivesTakeoverLaterActivationAndMissingMutableProjections() {
        Harness harness = started("replay");
        PlanReplanRequest request = replanRequest(
                harness,
                "replay",
                currentPlan(harness),
                List.of(
                        PersistenceFixtures.step(PersistenceFixtures.STEP_1, Set.of()),
                        PersistenceFixtures.step(
                                PersistenceFixtures.STEP_2,
                                Set.of(PersistenceFixtures.STEP_1))));
        PersistedPlanReplan original = requireApplied(harness.replans().replan(request));
        harness.clock().set(PersistenceFixtures.T0.plusSeconds(60));
        LeaseRecord takeover = requireApplied(harness.leases().acquire(
                harness.plan().id(), "replay-owner", "replay-token",
                PersistenceFixtures.T0.plus(Duration.ofMinutes(2))));
        Plan replanned = currentPlan(harness);
        requireApplied(harness.activations().activate(
                PersistenceFixtures.stepActivationRequest(
                        replanned,
                        harness.state().checkpoints.get(replanned.id()).checkpoint(),
                        3,
                        2,
                        "replay-token",
                        takeover.fencingToken(),
                        PersistenceFixtures.STEP_1,
                        "activation-after-replan",
                        3)));
        harness.state().plans.clear();
        harness.state().checkpoints.clear();
        harness.state().leases.clear();
        harness.clock().failOnObservation();

        assertEquals(original, requireReplayed(harness.replans().replan(request)));
    }

    @Test
    void unsafeOrInvalidCandidatesRejectWithoutBusinessWrites() {
        Harness active = started("active");
        requireApplied(active.activations().activate(PersistenceFixtures.stepActivationRequest(
                active.plan(), TOKEN, 1, "active-step")));
        PlanReplanRequest activeRequest = replanRequest(
                active, "active", currentPlan(active), currentPlan(active).latestRevision().steps());
        Snapshot activeBefore = snapshot(active.state());
        assertFailure(active.replans().replan(activeRequest),
                PersistenceErrorCode.PLAN_REPLAN_NOT_ELIGIBLE, "planReplan");
        assertUnchangedExceptTime(activeBefore, active.state());

        Harness malformed = started("malformed");
        Plan source = currentPlan(malformed);
        PlanRevision invalidFacts = new PlanRevision(
                new PlanRevisionId("revision-invalid-facts"),
                source.taskFrameId(),
                2,
                Optional.of(source.latestRevision().id()),
                "invalid replan",
                PersistenceFixtures.T0.plusSeconds(2),
                source.latestRevision().steps(),
                Map.of(PersistenceFixtures.STEP_1, new CompletionFact(
                        PersistenceFixtures.STEP_1,
                        "new-fact",
                        PersistenceFixtures.T0.plusSeconds(2),
                        List.of())));
        PlanReplanRequest invalid = new PlanReplanRequest(
                source.id(), TOKEN, 1, source.latestRevision().id(), 1, 2, 1,
                PersistenceFixtures.event("replan-invalid", source.taskFrameId(), source.id(), 2),
                invalidFacts,
                checkpointFor(source, invalidFacts, 2));
        Snapshot malformedBefore = snapshot(malformed.state());
        assertFailure(malformed.replans().replan(invalid),
                PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                "request.replannedRevision");
        assertUnchangedExceptTime(malformedBefore, malformed.state());
    }

    private static Harness started(String suffix) {
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
                        plan, TOKEN, lease.fencingToken(), "start-replan-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state),
                plan,
                TOKEN,
                lease.fencingToken(),
                PersistenceFixtures.workspaceSpec("replan-" + suffix));
        return new Harness(
                state,
                clock,
                plan,
                leases,
                new InMemoryStepActivationRepository(state),
                new InMemoryStepCompletionRepository(state),
                new InMemoryPlanReplanRepository(state),
                new InMemoryExecutionStartRecoveryRepository(state));
    }

    private static void completeFirstStep(Harness harness, String suffix) {
        requireApplied(harness.activations().activate(PersistenceFixtures.stepActivationRequest(
                harness.plan(), TOKEN, 1, "activation-before-replan-" + suffix)));
        Plan source = currentPlan(harness);
        Checkpoint checkpoint = harness.state().checkpoints.get(source.id()).checkpoint();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "completed-before-replan-" + suffix,
                checkpoint.createdAt().plusSeconds(1),
                List.of());
        PlanRevision completed = new PlanRevision(
                new PlanRevisionId("revision-completed-before-replan-" + suffix),
                source.taskFrameId(),
                2,
                Optional.of(source.latestRevision().id()),
                "complete before replan",
                checkpoint.createdAt().plusSeconds(1),
                source.latestRevision().steps(),
                Map.of(PersistenceFixtures.STEP_1, fact));
        Checkpoint completedCheckpoint = new Checkpoint(
                source.taskFrameId(),
                source.id(),
                completed.id(),
                completed.number(),
                4,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.SUCCEEDED,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                List.of(),
                checkpoint.createdAt().plusSeconds(1));
        requireApplied(harness.completions().complete(new StepCompletionRequest(
                source.id(), TOKEN, 1,
                source.latestRevision().id(), source.latestRevision().number(),
                3, 3, PersistenceFixtures.STEP_1, fact,
                PersistenceFixtures.event("completion-before-replan-" + suffix,
                        source.taskFrameId(), source.id(), 4),
                completed, completedCheckpoint)));
    }

    private static PlanReplanRequest replanRequest(
            Harness harness,
            String suffix,
            Plan source,
            List<PlanStep> steps) {
        Checkpoint checkpoint = harness.state().checkpoints.get(source.id()).checkpoint();
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-replan-" + suffix),
                source.taskFrameId(),
                source.latestRevision().number() + 1,
                Optional.of(source.latestRevision().id()),
                "replan " + suffix,
                checkpoint.createdAt().plusSeconds(1),
                steps,
                source.latestRevision().completedFacts());
        long eventSequence = checkpoint.lastEventSequence() + 1;
        return new PlanReplanRequest(
                source.id(), TOKEN, 1,
                source.latestRevision().id(), source.latestRevision().number(),
                harness.state().checkpoints.get(source.id()).version(),
                checkpoint.lastEventSequence(),
                PersistenceFixtures.event("replan-event-" + suffix,
                        source.taskFrameId(), source.id(), eventSequence),
                revision,
                checkpointFor(source, revision, eventSequence));
    }

    private static Checkpoint checkpointFor(
            Plan source,
            PlanRevision revision,
            long eventSequence) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        revision.steps().forEach(step -> states.put(step.id(),
                revision.completedFacts().containsKey(step.id())
                        ? StepExecutionState.SUCCEEDED
                        : StepExecutionState.NOT_STARTED));
        return new Checkpoint(
                source.taskFrameId(),
                source.id(),
                revision.id(),
                revision.number(),
                eventSequence,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                PersistenceFixtures.T0.plusSeconds(eventSequence));
    }

    private static Plan currentPlan(Harness harness) {
        return harness.state().plans.get(harness.plan().id());
    }

    private static void assertAdvanced(PersistenceResult<?> result) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                result.failure().orElseThrow().code());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static <T> T requireReplayed(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.REPLAYED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static Snapshot snapshot(InMemoryState state) {
        return new Snapshot(
                new LinkedHashMap<>(state.plans),
                new LinkedHashMap<>(state.eventsById),
                new LinkedHashMap<>(state.eventStreams),
                new LinkedHashMap<>(state.checkpoints),
                new LinkedHashMap<>(state.planReplans),
                new LinkedHashMap<>(state.executionMutationHeads),
                new LinkedHashMap<>(state.executionMutationLinks),
                state.leaseTimeHighWater);
    }

    private static void assertUnchangedExceptReplan(
            Snapshot before,
            InMemoryState state) {
        assertEquals(before.plans().size() + 1, state.plans.get(PersistenceFixtures.PLAN_ID)
                .revisions().size());
        assertEquals(before.eventsById().size() + 1, state.eventsById.size());
        assertEquals(before.checkpoints().size(), state.checkpoints.size());
        assertEquals(before.planReplans().size() + 1, state.planReplans.size());
    }

    private static void assertUnchangedExceptTime(Snapshot before, InMemoryState state) {
        assertEquals(before.plans(), state.plans);
        assertEquals(before.eventsById(), state.eventsById);
        assertEquals(before.eventStreams(), state.eventStreams);
        assertEquals(before.checkpoints(), state.checkpoints);
        assertEquals(before.planReplans(), state.planReplans);
        assertEquals(before.heads(), state.executionMutationHeads);
        assertEquals(before.links(), state.executionMutationLinks);
    }

    private record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            InMemoryLeaseRepository leases,
            StepActivationRepository activations,
            StepCompletionRepository completions,
            PlanReplanRepository replans,
            ExecutionStartRecoveryRepository recovery) {
    }

    private record Snapshot(
            Map<?, ?> plans,
            Map<?, ?> eventsById,
            Map<?, ?> eventStreams,
            Map<?, ?> checkpoints,
            Map<?, ?> planReplans,
            Map<?, ?> heads,
            Map<?, ?> links,
            java.time.Instant leaseTimeHighWater) {
    }
}
