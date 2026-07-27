package io.paperagent.v2.contracts;

import java.util.List;
import java.util.Set;

public record PlanStep(
        PlanStepId id,
        String intent,
        String expectedOutcome,
        Set<PlanStepId> dependencies,
        List<String> completionCriteria,
        BoundedExecutionHints executionHints) {

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
    }
}
