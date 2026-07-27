package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.PlanId;

import java.util.Objects;

public record RecoveryRequired(PlanId planId)
        implements FreshExecutionDecision {
    public RecoveryRequired {
        Objects.requireNonNull(planId, "planId");
    }
}
