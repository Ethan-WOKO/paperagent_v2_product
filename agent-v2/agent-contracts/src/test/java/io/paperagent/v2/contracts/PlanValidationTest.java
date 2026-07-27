package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_2;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PlanValidationTest {
    @Test
    void rejectsDuplicateStepIds() {
        PlanStep duplicate = ContractFixtures.step(STEP_1, Set.of());
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1, Optional.empty(), List.of(duplicate, duplicate), Map.of()));
        assertEquals(ViolationCode.DUPLICATE_ID, exception.primaryCode());
    }

    @Test
    void rejectsUnknownDependency() {
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1,
                Optional.empty(),
                List.of(ContractFixtures.step(STEP_1, Set.of(new PlanStepId("missing")))),
                Map.of()));
        assertEquals(ViolationCode.UNKNOWN_STEP_DEPENDENCY, exception.primaryCode());
    }

    @Test
    void rejectsSelfDependency() {
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1,
                Optional.empty(),
                List.of(ContractFixtures.step(STEP_1, Set.of(STEP_1))),
                Map.of()));
        assertEquals(ViolationCode.SELF_DEPENDENCY, exception.primaryCode());
    }

    @Test
    void rejectsMultiStepCycle() {
        PlanStep first = ContractFixtures.step(STEP_1, Set.of(STEP_2));
        PlanStep second = ContractFixtures.step(STEP_2, Set.of(STEP_1));
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1, Optional.empty(), List.of(first, second), Map.of()));
        assertEquals(ViolationCode.STEP_DEPENDENCY_CYCLE, exception.primaryCode());
    }

    @Test
    void rejectsParentMismatch() {
        PlanRevision first = ContractFixtures.revision1();
        PlanRevision second = revision(
                2,
                Optional.of(new PlanRevisionId("wrong-parent")),
                first.steps(),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, second));
        assertTrue(exception.violations().stream()
                .anyMatch(violation -> violation.code() == ViolationCode.REVISION_PARENT_MISMATCH));
    }

    @Test
    void rejectsNonMonotonicRevisionNumber() {
        PlanRevision first = ContractFixtures.revision1();
        PlanRevision third = revision(
                3,
                Optional.of(first.id()),
                first.steps(),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, third));
        assertTrue(exception.violations().stream()
                .anyMatch(violation -> violation.code() == ViolationCode.REVISION_NOT_MONOTONIC));
    }

    @Test
    void rejectsTaskFrameMismatch() {
        PlanRevision wrong = new PlanRevision(
                new PlanRevisionId("revision-other"),
                new TaskFrameId("task-other"),
                1,
                Optional.empty(),
                "wrong task",
                T0,
                List.of(ContractFixtures.step(STEP_1, Set.of())),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> new Plan(ContractFixtures.PLAN_ID, TASK_ID, List.of(wrong)));
        assertEquals(ViolationCode.TASK_FRAME_MISMATCH, exception.primaryCode());
    }

    @Test
    void rejectsRemovalOfCompletedStepFact() {
        PlanRevision first = completedFirstRevision();
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                List.of(ContractFixtures.step(STEP_2, Set.of())),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, second));
        assertEquals(ViolationCode.COMPLETED_FACT_REMOVED, exception.primaryCode());
        assertEquals(
                "planRevision.completedFacts." + STEP_1.value(),
                exception.violations().get(0).path());
    }

    @Test
    void rejectsRedefinitionOfCompletedStep() {
        PlanRevision first = completedFirstRevision();
        PlanStep rewritten = new PlanStep(
                STEP_1,
                "rewritten intent",
                "rewritten outcome",
                Set.of(),
                List.of("different"),
                new BoundedExecutionHints(1, Duration.ofSeconds(5)));
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                List.of(rewritten, ContractFixtures.step(STEP_2, Set.of(STEP_1))),
                first.completedFacts());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, second));
        assertEquals(ViolationCode.COMPLETED_FACT_REDEFINED, exception.primaryCode());
        assertEquals(
                "planRevision.completedFacts." + STEP_1.value(),
                exception.violations().get(0).path());
    }

    @Test
    void completedFactRewriteHasStableCode() {
        PlanRevision first = completedFirstRevision();
        CompletionFact changed = new CompletionFact(STEP_1, "contradictory", T0.plusSeconds(1), List.of());
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                first.steps(),
                Map.of(STEP_1, changed));
        List<ContractViolation> violations =
                PlanValidators.validateCompletedFactPreservation(first, second);
        assertEquals(ViolationCode.COMPLETED_FACT_REDEFINED, violations.get(0).code());
        assertEquals(
                "planRevision.completedFacts." + STEP_1.value(),
                violations.get(0).path());
    }

    @Test
    void acceptsFirstFactForAnUnchangedPriorStepViaValidatorAndPlan() {
        PlanRevision first = revision(
                1,
                Optional.empty(),
                List.of(
                        ContractFixtures.step(STEP_1, Set.of()),
                        ContractFixtures.step(STEP_2, Set.of(STEP_1))),
                Map.of());
        CompletionFact newlyCompleted =
                new CompletionFact(STEP_2, "outcome-v2", T0.plusSeconds(2), List.of());
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                first.steps(),
                Map.of(STEP_2, newlyCompleted));

        assertEquals(
                List.of(),
                PlanValidators.validateCompletedFactPreservation(first, second));
        Plan plan = ContractFixtures.plan(first, second);
        assertEquals(newlyCompleted, plan.latestRevision().completedFacts().get(STEP_2));
    }

    @Test
    void rejectsFirstFactForStepIntroducedInCurrentRevisionViaValidatorAndPlan() {
        PlanStep priorStep = ContractFixtures.step(STEP_1, Set.of());
        PlanRevision first = revision(
                1,
                Optional.empty(),
                List.of(priorStep),
                Map.of());
        PlanStep newlyIntroduced = ContractFixtures.step(STEP_2, Set.of(STEP_1));
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                List.of(priorStep, newlyIntroduced),
                Map.of(STEP_2, fact(STEP_2, "unexecuted-new-step")));
        String path = "planRevision.completedFacts." + STEP_2.value();

        assertSingleViolation(
                PlanValidators.validateCompletedFactPreservation(first, second),
                ViolationCode.INCONSISTENT_REFERENCE,
                path);
        assertPlanViolation(
                first,
                second,
                ViolationCode.INCONSISTENT_REFERENCE,
                path);
    }

    @Test
    void rejectsFirstFactForRewrittenPriorStepViaValidatorAndPlan() {
        PlanStep priorStep = ContractFixtures.step(STEP_1, Set.of());
        PlanRevision first = revision(
                1,
                Optional.empty(),
                List.of(priorStep),
                Map.of());
        PlanStep rewritten = new PlanStep(
                STEP_1,
                "rewritten before first fact",
                priorStep.expectedOutcome(),
                priorStep.dependencies(),
                priorStep.completionCriteria(),
                priorStep.executionHints());
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                List.of(rewritten),
                Map.of(STEP_1, fact(STEP_1, "rewritten-new-fact")));
        String path = "planRevision.completedFacts." + STEP_1.value();

        assertSingleViolation(
                PlanValidators.validateCompletedFactPreservation(first, second),
                ViolationCode.COMPLETED_FACT_REDEFINED,
                path);
        assertPlanViolation(
                first,
                second,
                ViolationCode.COMPLETED_FACT_REDEFINED,
                path);
    }

    @Test
    void factHistoryViolationsAreGloballySortedAcrossMapInsertionOrders() {
        PlanStepId stepA = new PlanStepId("step-a");
        PlanStepId stepM = new PlanStepId("step-m");
        PlanStepId stepY = new PlanStepId("step-y");
        PlanStepId stepZ = new PlanStepId("step-z");
        PlanStep priorM = ContractFixtures.step(stepM, Set.of());
        PlanStep priorZ = ContractFixtures.step(stepZ, Set.of());
        PlanStep newA = ContractFixtures.step(stepA, Set.of());
        PlanStep newY = ContractFixtures.step(stepY, Set.of());

        LinkedHashMap<PlanStepId, CompletionFact> previousFactsForward = new LinkedHashMap<>();
        previousFactsForward.put(stepM, fact(stepM, "prior-m"));
        previousFactsForward.put(stepZ, fact(stepZ, "prior-z"));
        LinkedHashMap<PlanStepId, CompletionFact> previousFactsReverse = new LinkedHashMap<>();
        previousFactsReverse.put(stepZ, fact(stepZ, "prior-z"));
        previousFactsReverse.put(stepM, fact(stepM, "prior-m"));
        LinkedHashMap<PlanStepId, CompletionFact> currentFactsReverse = new LinkedHashMap<>();
        currentFactsReverse.put(stepY, fact(stepY, "new-y"));
        currentFactsReverse.put(stepA, fact(stepA, "new-a"));
        LinkedHashMap<PlanStepId, CompletionFact> currentFactsForward = new LinkedHashMap<>();
        currentFactsForward.put(stepA, fact(stepA, "new-a"));
        currentFactsForward.put(stepY, fact(stepY, "new-y"));

        PlanRevision previousForward = revision(
                1,
                Optional.empty(),
                List.of(priorM, priorZ),
                previousFactsForward);
        PlanRevision previousReverse = revision(
                1,
                Optional.empty(),
                List.of(priorM, priorZ),
                previousFactsReverse);
        List<PlanStep> currentSteps = List.of(newA, priorM, newY, priorZ);
        PlanRevision currentReverse = revision(
                2,
                Optional.of(previousForward.id()),
                currentSteps,
                currentFactsReverse);
        PlanRevision currentForward = revision(
                2,
                Optional.of(previousReverse.id()),
                currentSteps,
                currentFactsForward);

        List<ContractViolation> reverseOrderViolations =
                PlanValidators.validateCompletedFactPreservation(
                        previousForward,
                        currentReverse);
        List<ContractViolation> forwardOrderViolations =
                PlanValidators.validateCompletedFactPreservation(
                        previousReverse,
                        currentForward);

        assertEquals(reverseOrderViolations, forwardOrderViolations);
        assertEquals(
                List.of(
                        "planRevision.completedFacts.step-a",
                        "planRevision.completedFacts.step-m",
                        "planRevision.completedFacts.step-y",
                        "planRevision.completedFacts.step-z"),
                reverseOrderViolations.stream().map(ContractViolation::path).toList());
        assertEquals(
                List.of(
                        ViolationCode.INCONSISTENT_REFERENCE,
                        ViolationCode.COMPLETED_FACT_REMOVED,
                        ViolationCode.INCONSISTENT_REFERENCE,
                        ViolationCode.COMPLETED_FACT_REMOVED),
                reverseOrderViolations.stream().map(ContractViolation::code).toList());
        assertPlanViolation(
                previousForward,
                currentReverse,
                ViolationCode.INCONSISTENT_REFERENCE,
                "planRevision.completedFacts.step-a");
        assertPlanViolation(
                previousReverse,
                currentForward,
                ViolationCode.INCONSISTENT_REFERENCE,
                "planRevision.completedFacts.step-a");
    }

    @Test
    void publicStepGraphValidatorReturnsStableViolationForNullElement() {
        List<ContractViolation> violations =
                PlanValidators.validateStepGraph(Arrays.asList((PlanStep) null));
        assertEquals(ViolationCode.NULL_COLLECTION_ELEMENT, violations.get(0).code());
    }

    @Test
    void publicCompletedFactValidatorReturnsStableViolationForMissingRevision() {
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                PlanValidators.validateCompletedFactPreservation(null, ContractFixtures.revision1())
                        .get(0).code());
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                PlanValidators.validateCompletedFactPreservation(ContractFixtures.revision1(), null)
                        .get(0).code());
    }

    @Test
    void publicValidatorsDoNotThrowForOtherExpectedNullInputs() {
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                PlanValidators.validateHistory(null, null).get(0).code());
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                CheckpointValidators.validate(null, null, null, null).get(0).code());
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                EventValidators.validateNext(null, null).get(0).code());
    }

    @Test
    void publicHistoryValidatorReturnsStableViolationForNullRevisionElement() {
        assertEquals(
                ViolationCode.NULL_COLLECTION_ELEMENT,
                PlanValidators.validateHistory(
                        TASK_ID, Arrays.asList((PlanRevision) null)).get(0).code());
    }

    private static PlanRevision completedFirstRevision() {
        return revision(
                1,
                Optional.empty(),
                List.of(ContractFixtures.step(STEP_1, Set.of()), ContractFixtures.step(STEP_2, Set.of(STEP_1))),
                Map.of(STEP_1, new CompletionFact(STEP_1, "outcome-v1", T0, List.of())));
    }

    private static CompletionFact fact(PlanStepId stepId, String outcome) {
        return new CompletionFact(stepId, outcome, T0.plusSeconds(2), List.of());
    }

    private static void assertSingleViolation(
            List<ContractViolation> violations,
            ViolationCode expectedCode,
            String expectedPath) {
        assertEquals(1, violations.size());
        assertEquals(expectedCode, violations.get(0).code());
        assertEquals(expectedPath, violations.get(0).path());
    }

    private static void assertPlanViolation(
            PlanRevision previous,
            PlanRevision current,
            ViolationCode expectedCode,
            String expectedPath) {
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(previous, current));
        assertEquals(expectedCode, exception.primaryCode());
        assertEquals(expectedPath, exception.violations().get(0).path());
    }

    private static PlanRevision revision(
            long number,
            Optional<PlanRevisionId> parent,
            List<PlanStep> steps,
            Map<PlanStepId, CompletionFact> facts) {
        return new PlanRevision(
                new PlanRevisionId("revision-" + number),
                TASK_ID,
                number,
                parent,
                "revision reason",
                T0.plusSeconds(number),
                steps,
                facts);
    }
}
