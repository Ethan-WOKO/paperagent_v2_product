package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.runtime.routing.RoutingDecision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class NextPlanRevisionTestFixtures {
    static final PlanId PLAN_ID = new PlanId("plan-next-revision");
    static final TaskFrameId TASK_FRAME_ID = new TaskFrameId("task-next-revision");
    static final PlanRevisionId FIRST_REVISION_ID =
            new PlanRevisionId("revision-next-1");
    static final PlanRevisionId SECOND_REVISION_ID =
            new PlanRevisionId("revision-next-2");
    static final PlanRevisionId NEXT_REVISION_ID =
            new PlanRevisionId("revision-next-3");
    static final PlanStepId FIRST_STEP_ID = new PlanStepId("step-next-1");
    static final PlanStepId SECOND_STEP_ID = new PlanStepId("step-next-2");
    static final Instant NEXT_CREATED_AT = Instant.parse("2026-07-24T06:00:00Z");

    private NextPlanRevisionTestFixtures() {
    }

    static Plan standardCurrentPlan() {
        PlanRevision first = revision(
                FIRST_REVISION_ID,
                1,
                Optional.empty(),
                steps(),
                Map.of());
        return new Plan(PLAN_ID, TASK_FRAME_ID, List.of(first));
    }

    static Plan currentPlanWithOneCompletedFact() {
        PlanRevision first = revision(
                FIRST_REVISION_ID,
                1,
                Optional.empty(),
                steps(),
                Map.of());
        PlanRevision second = revision(
                SECOND_REVISION_ID,
                2,
                Optional.of(FIRST_REVISION_ID),
                steps(),
                Map.of(FIRST_STEP_ID, fact(FIRST_STEP_ID, "outcome-first", 1)));
        return new Plan(PLAN_ID, TASK_FRAME_ID, List.of(first, second));
    }

    static List<PlanStep> steps() {
        return List.of(
                PlanningTestFixtures.step(FIRST_STEP_ID, Set.of()),
                PlanningTestFixtures.step(SECOND_STEP_ID, Set.of(FIRST_STEP_ID)));
    }

    static CompletionFact fact(PlanStepId stepId, String outcome, long seconds) {
        return new CompletionFact(
                stepId,
                outcome,
                Instant.parse("2026-07-24T05:30:00Z").plusSeconds(seconds),
                List.of(new ReceiptId("receipt-" + stepId.value())));
    }

    static NextPlanRevisionDraft draft(String reason, List<PlanStep> steps) {
        return new NextPlanRevisionDraft(reason, steps);
    }

    static NextPlanRevisionFreezeRequest request(
            RoutingDecision routingDecision,
            Plan currentPlan,
            PlanRevisionId nextRevisionId,
            NextPlanRevisionDraft draft,
            Instant createdAt,
            Map<PlanStepId, CompletionFact> delta) {
        return new NextPlanRevisionFreezeRequest(
                routingDecision,
                currentPlan,
                nextRevisionId,
                draft,
                createdAt,
                delta);
    }

    static NextPlanRevisionFreezeRequest request(
            Plan currentPlan,
            NextPlanRevisionDraft draft,
            Map<PlanStepId, CompletionFact> delta) {
        return request(
                PlanningTestFixtures.declaredRequirementDecision(
                        "routing-next-revision-default"),
                currentPlan,
                NEXT_REVISION_ID,
                draft,
                NEXT_CREATED_AT,
                delta);
    }

    private static PlanRevision revision(
            PlanRevisionId id,
            long number,
            Optional<PlanRevisionId> parent,
            List<PlanStep> steps,
            Map<PlanStepId, CompletionFact> facts) {
        return new PlanRevision(
                id,
                TASK_FRAME_ID,
                number,
                parent,
                "revision " + number,
                Instant.parse("2026-07-24T05:00:00Z").plusSeconds(number),
                steps,
                facts);
    }
}
