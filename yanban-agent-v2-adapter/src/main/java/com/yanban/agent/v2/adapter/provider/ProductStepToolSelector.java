package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.runtime.execution.kernel.StepTurnInput;

import java.util.List;

/** Resolves the exact tools authorized by persisted product authority. */
@FunctionalInterface
public interface ProductStepToolSelector {
    List<ToolDescriptor> select(StepTurnInput input);
}
