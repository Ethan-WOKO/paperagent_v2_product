package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.PlanId;

import java.util.Objects;

public record FreshLeaseAdmissionEligible(PlanId planId)
        implements FreshExecutionDecision {
    public FreshLeaseAdmissionEligible {
        Objects.requireNonNull(planId, "planId");
    }
}
