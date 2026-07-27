package io.paperagent.v2.contracts;

/**
 * Immutable provider-neutral intent for one externally observable effect.
 */
public record EffectIntent(
        ToolCallId toolCallId,
        PlanId planId,
        PlanStepId stepId,
        String kind,
        ObjectValue arguments) {

    public EffectIntent {
        toolCallId = Contracts.required(toolCallId, "effectIntent.toolCallId");
        planId = Contracts.required(planId, "effectIntent.planId");
        stepId = Contracts.required(stepId, "effectIntent.stepId");
        kind = Contracts.id(kind, "effectIntent.kind");
        arguments = Contracts.required(arguments, "effectIntent.arguments");
    }

    @Override
    public String toString() {
        return "EffectIntent["
                + "toolCallId=<provided>, "
                + "planId=<provided>, "
                + "stepId=<provided>, "
                + "kind=<provided>, "
                + "arguments=<provided>]";
    }
}
