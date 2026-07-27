package io.paperagent.v2.runtime.execution.loop;

/** Runs a provider-neutral, bounded sequence of single Step turns. */
@FunctionalInterface
public interface BoundedStepAgentLoop {
    BoundedStepAgentLoopOutcome run(BoundedStepAgentLoopRequest request);
}
