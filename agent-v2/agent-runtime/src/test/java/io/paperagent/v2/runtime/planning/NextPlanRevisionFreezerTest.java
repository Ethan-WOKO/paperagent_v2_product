package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NextPlanRevisionFreezerTest {
    @Test
    void emptyDeltaAppendsExactNextRevisionAndPreservesCurrentAuthority() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        Plan currentSnapshot = new Plan(
                currentPlan.id(),
                currentPlan.taskFrameId(),
                currentPlan.revisions());
        NextPlanRevisionDraft draft = NextPlanRevisionTestFixtures.draft(
                "Refine the execution plan",
                currentPlan.latestRevision().steps());
        PlanRevisionId nextRevisionId = new PlanRevisionId("revision-provenance-next");
        var createdAt = NextPlanRevisionTestFixtures.NEXT_CREATED_AT.plusSeconds(10);
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        PlanningTestFixtures.declaredRequirementDecision(
                                "routing-next-provenance"),
                        currentPlan,
                        nextRevisionId,
                        draft,
                        createdAt,
                        Map.of());

        Plan result = new DeterministicNextPlanRevisionFreezer().freeze(request);
        PlanRevision candidate = result.latestRevision();

        assertEquals(currentPlan.id(), result.id());
        assertEquals(currentPlan.taskFrameId(), result.taskFrameId());
        assertEquals(
                List.of(currentPlan.latestRevision(), candidate),
                result.revisions());
        assertEquals(nextRevisionId, candidate.id());
        assertEquals(currentPlan.taskFrameId(), candidate.taskFrameId());
        assertEquals(currentPlan.latestRevision().number() + 1, candidate.number());
        assertEquals(
                Optional.of(currentPlan.latestRevision().id()),
                candidate.parentRevisionId());
        assertEquals(draft.reason(), candidate.reason());
        assertEquals(draft.steps(), candidate.steps());
        assertEquals(createdAt, candidate.createdAt());
        assertEquals(Map.of(), candidate.completedFacts());
        assertEquals(currentSnapshot, currentPlan);
    }

    @Test
    void incompleteAssessmentPersistentReasonIsAccepted() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        PlanningTestFixtures.incompleteAssessmentDecision(
                                "routing-next-incomplete"),
                        currentPlan,
                        new PlanRevisionId("revision-next-incomplete"),
                        NextPlanRevisionTestFixtures.draft(
                                "Incomplete assessment fail-closed revision",
                                currentPlan.latestRevision().steps()),
                        NextPlanRevisionTestFixtures.NEXT_CREATED_AT,
                        Map.of());

        Plan result = new DeterministicNextPlanRevisionFreezer().freeze(request);

        assertEquals(request.nextRevisionId(), result.latestRevision().id());
        assertEquals(currentPlan.revisions().size() + 1, result.revisions().size());
    }

    @Test
    void equalInputsAcrossInstancesRemainDeterministicWhenInterleavedAndReplayed() {
        NextPlanRevisionFreezer firstFreezer =
                new DeterministicNextPlanRevisionFreezer();
        NextPlanRevisionFreezer secondFreezer =
                new DeterministicNextPlanRevisionFreezer();
        Plan firstCurrent = NextPlanRevisionTestFixtures.standardCurrentPlan();
        NextPlanRevisionFreezeRequest firstRequest =
                NextPlanRevisionTestFixtures.request(
                        firstCurrent,
                        NextPlanRevisionTestFixtures.draft(
                                "Deterministic revision",
                                firstCurrent.latestRevision().steps()),
                        Map.of());
        Plan equalCurrent = NextPlanRevisionTestFixtures.standardCurrentPlan();
        NextPlanRevisionFreezeRequest equalFirstRequest =
                NextPlanRevisionTestFixtures.request(
                        equalCurrent,
                        NextPlanRevisionTestFixtures.draft(
                                "Deterministic revision",
                                equalCurrent.latestRevision().steps()),
                        Map.of());
        Plan secondCurrent = NextPlanRevisionTestFixtures.standardCurrentPlan();
        NextPlanRevisionFreezeRequest secondRequest =
                NextPlanRevisionTestFixtures.request(
                        PlanningTestFixtures.incompleteAssessmentDecision(
                                "routing-next-interleaved"),
                        secondCurrent,
                        new PlanRevisionId("revision-next-interleaved"),
                        NextPlanRevisionTestFixtures.draft(
                                "Independent revision",
                                secondCurrent.latestRevision().steps()),
                        NextPlanRevisionTestFixtures.NEXT_CREATED_AT.plusSeconds(1),
                        Map.of());

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
        assertEquals(NextPlanRevisionTestFixtures.standardCurrentPlan(), firstCurrent);
        assertEquals(NextPlanRevisionTestFixtures.standardCurrentPlan(), equalCurrent);
        assertEquals(NextPlanRevisionTestFixtures.standardCurrentPlan(), secondCurrent);
    }
}
