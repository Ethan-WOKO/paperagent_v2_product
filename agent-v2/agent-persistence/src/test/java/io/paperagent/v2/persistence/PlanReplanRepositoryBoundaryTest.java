package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanReplanRepositoryBoundaryTest {
    private static final String TOKEN = "boundary-replan-token";

    @Test
    void publicValuesValidateEveryComponentAndRedactStateBearingPayloads() {
        PlanReplanRequest request = request();
        PersistedPlanReplan persisted = new PersistedPlanReplan(
                request.planId(),
                "owner-opaque",
                1,
                request.replanEvent(),
                request.replannedRevision(),
                new VersionedCheckpoint(3, request.replannedCheckpoint()));

        for (String sentinel : List.of(
                "replan-token-opaque",
                "owner-opaque",
                "replan-event-opaque",
                "revision-replan-opaque",
                "replan opaque")) {
            assertFalse(request.toString().contains(sentinel), request.toString());
            assertFalse(persisted.toString().contains(sentinel), persisted.toString());
        }
        assertThrows(NullPointerException.class, () -> new PlanReplanRequest(
                null,
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.replanEvent(),
                request.replannedRevision(),
                request.replannedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new PlanReplanRequest(
                request.planId(),
                " ",
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.replanEvent(),
                request.replannedRevision(),
                request.replannedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new PlanReplanRequest(
                request.planId(),
                request.leaseToken(),
                1,
                request.expectedRevisionId(),
                1,
                1,
                1,
                request.replanEvent(),
                request.replannedRevision(),
                request.replannedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new PersistedPlanReplan(
                request.planId(),
                "owner",
                1,
                request.replanEvent(),
                request.replannedRevision(),
                new VersionedCheckpoint(2, request.replannedCheckpoint())));
    }

    @Test
    void invalidRepositoryInputAndTornReplanProvenanceRejectBeforeClock() {
        PersistenceFixtures.MutableCountingClock emptyClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence empty = new InMemoryPersistence(emptyClock);
        emptyClock.failOnObservation();
        assertFailure(empty.planReplans().replan(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "request");

        Scenario scenario = scenario("torn");
        PlanReplanRequest request = request(scenario.plan(), "torn", TOKEN);
        requireApplied(scenario.replans().replan(request));
        scenario.state().planReplans.remove(scenario.plan().id());
        scenario.clock().failOnObservation();

        assertFailure(scenario.replans().replan(request),
                PersistenceErrorCode.PLAN_REPLAN_PARTIAL_STATE, "planReplan");
    }

    @Test
    void malformedCheckpointAndGlobalEventCollisionMakeNoBusinessWrite() {
        Scenario scenario = scenario("rejected");
        PlanReplanRequest request = request(scenario.plan(), "rejected", TOKEN);
        Checkpoint wrongCheckpoint = new Checkpoint(
                request.replannedCheckpoint().taskFrameId(),
                request.replannedCheckpoint().planId(),
                request.replannedCheckpoint().revisionId(),
                request.replannedCheckpoint().revisionNumber(),
                request.replannedCheckpoint().lastEventSequence(),
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                request.replannedCheckpoint().receiptReferences(),
                request.replannedCheckpoint().createdAt());
        PlanReplanRequest malformed = new PlanReplanRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(), request.expectedEventHeadSequence(),
                request.replanEvent(), request.replannedRevision(), wrongCheckpoint);
        assertFailure(scenario.replans().replan(malformed),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.replannedCheckpoint");
        assertEquals(1, scenario.state().plans.get(scenario.plan().id()).revisions().size());
        assertEquals(1, scenario.state().eventStreams.get(scenario.plan().id()).size());
        assertFalse(scenario.state().planReplans.containsKey(scenario.plan().id()));
    }

    private static Scenario scenario(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(), plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        LeaseRecord lease = requireApplied(new InMemoryLeaseRepository(state).acquire(
                plan.id(), "boundary-owner", TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "start-replan-boundary-" + suffix)));
        return new Scenario(state, clock, plan, new InMemoryPlanReplanRepository(state));
    }

    private static PlanReplanRequest request() {
        return request(PersistenceFixtures.plan(), "opaque", "replan-token-opaque");
    }

    private static PlanReplanRequest request(
            Plan plan,
            String suffix,
            String token) {
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-replan-" + suffix + "-opaque"),
                plan.taskFrameId(),
                2,
                Optional.of(plan.latestRevision().id()),
                "replan opaque",
                PersistenceFixtures.T0.plusSeconds(2),
                plan.latestRevision().steps(),
                plan.latestRevision().completedFacts());
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        revision.steps().forEach(step -> states.put(step.id(), StepExecutionState.NOT_STARTED));
        Checkpoint checkpoint = new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                PersistenceFixtures.T0.plusSeconds(2));
        return new PlanReplanRequest(
                plan.id(), token, 1,
                plan.latestRevision().id(), 1, 2, 1,
                PersistenceFixtures.event("replan-event-" + suffix + "-opaque",
                        plan.taskFrameId(), plan.id(), 2),
                revision, checkpoint);
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

    private record Scenario(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            PlanReplanRepository replans) {
    }
}
