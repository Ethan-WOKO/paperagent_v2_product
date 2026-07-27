package io.paperagent.v2.runtime.execution.context.composition;

@FunctionalInterface
public interface PlanExecutionContextComposer {
    PlanExecutionContextCompositionOutcome compose(
            PlanExecutionContextCompositionRequest request);
}
