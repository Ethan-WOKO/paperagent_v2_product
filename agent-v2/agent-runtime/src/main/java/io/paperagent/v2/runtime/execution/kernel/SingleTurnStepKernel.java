package io.paperagent.v2.runtime.execution.kernel;

/** Runs one provider-neutral decision turn for an already recovered active Step. */
@FunctionalInterface
public interface SingleTurnStepKernel {
    SingleTurnStepKernelOutcome run(SingleTurnStepKernelRequest request);
}
