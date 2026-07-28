package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ToolCallId;

/** Verifies that a receipt belongs to the expected literature-search step. */
public interface LiteratureIntentOwnershipSource {
    boolean owns(
            ToolCallId toolCallId,
            PlanId planId,
            PlanStepId stepId,
            String kind);
}
