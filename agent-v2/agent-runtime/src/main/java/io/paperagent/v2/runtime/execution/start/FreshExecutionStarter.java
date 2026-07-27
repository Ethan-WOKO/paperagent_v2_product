package io.paperagent.v2.runtime.execution.start;

@FunctionalInterface
public interface FreshExecutionStarter {
    FreshExecutionStartOutcome start(FreshExecutionStartRequest request);
}
