package io.paperagent.v2.runtime.execution.recovery.composition;

@FunctionalInterface
public interface ExecutionStartRecoverer {
    ExecutionStartRecoveryOutcome recover(
            ExecutionStartRecoveryRequest request);
}
