package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InitialPlanFreezerTest {
    @Test
    void declaredRequirementRoutePreservesProvenanceAndFixedInitialInvariants() {
        var routingDecision =
                PlanningTestFixtures.declaredRequirementDecision("routing-plan-provenance");
        var taskFrame = PlanningTestFixtures.taskFrame("task-plan-provenance");
        PlanId planId = new PlanId("plan-provenance");
        PlanRevisionId revisionId = new PlanRevisionId("revision-provenance");
        InitialPlanDraft draft = PlanningTestFixtures.twoStepDraft();
        Instant createdAt = Instant.parse("2026-07-24T05:10:00Z");
        InitialPlanFreezeRequest request = PlanningTestFixtures.request(
                routingDecision,
                taskFrame,
                planId,
                revisionId,
                draft,
                createdAt);

        Plan result = new DeterministicInitialPlanFreezer().freeze(request);
        PlanRevision revision = result.latestRevision();

        assertEquals(planId, result.id());
        assertEquals(taskFrame.id(), result.taskFrameId());
        assertEquals(List.of(revision), result.revisions());
        assertEquals(revisionId, revision.id());
        assertEquals(taskFrame.id(), revision.taskFrameId());
        assertEquals(1, revision.number());
        assertEquals(Optional.empty(), revision.parentRevisionId());
        assertEquals(draft.reason(), revision.reason());
        assertEquals(createdAt, revision.createdAt());
        assertEquals(draft.steps(), revision.steps());
        assertEquals(
                List.of(
                        PlanningTestFixtures.FIRST_STEP_ID,
                        PlanningTestFixtures.SECOND_STEP_ID),
                revision.steps().stream().map(step -> step.id()).toList());
        assertEquals(Map.of(), revision.completedFacts());
    }

    @Test
    void incompleteAssessmentPersistentRouteIsAccepted() {
        var taskFrame = PlanningTestFixtures.taskFrame("task-incomplete-plan");
        InitialPlanFreezeRequest request = PlanningTestFixtures.request(
                PlanningTestFixtures.incompleteAssessmentDecision(
                        "routing-incomplete-plan"),
                taskFrame,
                new PlanId("plan-incomplete"),
                new PlanRevisionId("revision-incomplete"),
                PlanningTestFixtures.twoStepDraft(),
                PlanningTestFixtures.CREATED_AT);

        Plan result = new DeterministicInitialPlanFreezer().freeze(request);

        assertEquals(request.planId(), result.id());
        assertEquals(taskFrame.id(), result.taskFrameId());
        assertEquals(request.initialRevisionId(), result.latestRevision().id());
    }

    @Test
    void equalInputsAcrossFreezersAndInterleavedRequestsRemainDeterministic() {
        InitialPlanFreezer firstFreezer = new DeterministicInitialPlanFreezer();
        InitialPlanFreezer secondFreezer = new DeterministicInitialPlanFreezer();
        InitialPlanFreezeRequest firstRequest =
                PlanningTestFixtures.request(PlanningTestFixtures.twoStepDraft());
        InitialPlanFreezeRequest equalFirstRequest =
                PlanningTestFixtures.request(PlanningTestFixtures.twoStepDraft());
        InitialPlanFreezeRequest secondRequest = PlanningTestFixtures.request(
                PlanningTestFixtures.incompleteAssessmentDecision(
                        "routing-interleaved-plan"),
                PlanningTestFixtures.taskFrame("task-interleaved-plan"),
                new PlanId("plan-interleaved"),
                new PlanRevisionId("revision-interleaved"),
                new InitialPlanDraft(
                        "Independent initial plan",
                        List.of(PlanningTestFixtures.step(
                                new PlanStepId("step-interleaved"),
                                java.util.Set.of()))),
                PlanningTestFixtures.CREATED_AT.plusSeconds(1));

        Plan firstResult = firstFreezer.freeze(firstRequest);
        Plan secondResult = secondFreezer.freeze(secondRequest);
        Plan firstCrossResult = secondFreezer.freeze(equalFirstRequest);
        Plan secondCrossResult = firstFreezer.freeze(secondRequest);
        Plan firstReplay = firstFreezer.freeze(equalFirstRequest);
        Plan secondReplay = secondFreezer.freeze(secondRequest);

        assertEquals(firstRequest, equalFirstRequest);
        assertEquals(firstResult, firstCrossResult);
        assertEquals(firstResult, firstReplay);
        assertEquals(secondResult, secondCrossResult);
        assertEquals(secondResult, secondReplay);
        assertNotEquals(firstResult, secondResult);
    }
}
