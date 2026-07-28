package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ToolCallId;

/** Verifies receipt ownership and the caller-authorized exact effect kind. */
@FunctionalInterface
public interface ExactIntentOwnershipSource {
    boolean owns(ToolCallId toolCallId, PlanId planId, PlanStepId stepId);
}
