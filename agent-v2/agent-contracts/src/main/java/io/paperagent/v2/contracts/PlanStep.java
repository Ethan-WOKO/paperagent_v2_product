package io.paperagent.v2.contracts;

import java.util.List;
import java.util.Set;

public record PlanStep(
        PlanStepId id,
        String intent,
        String expectedOutcome,
        Set<PlanStepId> dependencies,
        List<String> completionCriteria,
        BoundedExecutionHints executionHints,
        List<String> constraints,
        boolean mayChangeCandidate,
        String candidateValidationCompletionCondition,
        List<String> validationRequirementIds) {

    public PlanStep(
            PlanStepId id,
            String intent,
            String expectedOutcome,
            Set<PlanStepId> dependencies,
            List<String> completionCriteria,
            BoundedExecutionHints executionHints) {
        this(id, intent, expectedOutcome, dependencies, completionCriteria,
                executionHints, List.of(), false, null, List.of());
    }

    public PlanStep(
            PlanStepId id,
            String intent,
            String expectedOutcome,
            Set<PlanStepId> dependencies,
            List<String> completionCriteria,
            BoundedExecutionHints executionHints,
            List<String> constraints) {
        this(id, intent, expectedOutcome, dependencies, completionCriteria,
                executionHints, constraints, false, null, List.of());
    }

    public PlanStep(
            PlanStepId id,
            String intent,
            String expectedOutcome,
            Set<PlanStepId> dependencies,
            List<String> completionCriteria,
            BoundedExecutionHints executionHints,
            List<String> constraints,
            boolean mayChangeCandidate,
            String candidateValidationCompletionCondition) {
        this(id, intent, expectedOutcome, dependencies, completionCriteria,
                executionHints, constraints, mayChangeCandidate,
                candidateValidationCompletionCondition, List.of());
    }

    public PlanStep {
        Contracts.required(id, "planStep.id");
        intent = Contracts.text(intent, "planStep.intent");
        expectedOutcome = Contracts.text(expectedOutcome, "planStep.expectedOutcome");
        dependencies = Contracts.set(dependencies, "planStep.dependencies");
        completionCriteria = Contracts.list(completionCriteria, "planStep.completionCriteria").stream()
                .map(value -> Contracts.text(value, "planStep.completionCriteria[]"))
                .toList();
        if (completionCriteria.isEmpty()) {
            Contracts.fail(ViolationCode.REQUIRED_VALUE_MISSING, "planStep.completionCriteria",
                    "at least one completion criterion is required");
        }
        Contracts.required(executionHints, "planStep.executionHints");
        constraints = Contracts.list(constraints, "planStep.constraints").stream()
                .map(value -> Contracts.text(value, "planStep.constraints[]"))
                .toList();
        if (candidateValidationCompletionCondition != null) {
            candidateValidationCompletionCondition = Contracts.text(
                    candidateValidationCompletionCondition,
                    "planStep.candidateValidationCompletionCondition");
            if (!completionCriteria.contains(candidateValidationCompletionCondition)) {
                Contracts.fail(ViolationCode.INCONSISTENT_REFERENCE,
                        "planStep.candidateValidationCompletionCondition",
                        "candidate validation must name an exact completion criterion");
            }
        }
        validationRequirementIds = Contracts.list(
                validationRequirementIds,
                "planStep.validationRequirementIds").stream()
                .map(value -> Contracts.text(value,
                        "planStep.validationRequirementIds[]"))
                .toList();
        java.util.HashSet<String> boundRequirements = new java.util.HashSet<>();
        for (String requirementId : validationRequirementIds) {
            if (!boundRequirements.add(requirementId)) {
                Contracts.fail(ViolationCode.DUPLICATE_ID,
                        "planStep.validationRequirementIds[]",
                        "a validation requirement may be bound only once per Step");
            }
        }
    }
}
