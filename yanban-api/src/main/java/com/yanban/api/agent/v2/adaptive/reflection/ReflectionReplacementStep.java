package com.yanban.api.agent.v2.adaptive.reflection;

import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.ToolId;

/**
 * One validated replacement Step and its optional public capability binding.
 */
public record ReflectionReplacementStep(
        PlanStep step,
        String publicCapability,
        ToolId internalToolId) {

    public ReflectionReplacementStep {
        if (step == null) {
            throw new IllegalArgumentException("step is required");
        }
        if ((publicCapability == null) != (internalToolId == null)) {
            throw new IllegalArgumentException(
                    "capability alias and tool id must be supplied together");
        }
    }
}
