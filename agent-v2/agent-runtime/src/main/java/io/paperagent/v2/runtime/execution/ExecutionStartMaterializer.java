package io.paperagent.v2.runtime.execution;

/**
 * Purely materializes proposed execution-start values from a snapshot.
 *
 * <p>The result is neither execution admission nor a committed fact. Current
 * state and commit eligibility remain repository authority.
 */
@FunctionalInterface
public interface ExecutionStartMaterializer {
    MaterializedExecutionStart materialize(
            ExecutionStartMaterializationRequest request);
}
