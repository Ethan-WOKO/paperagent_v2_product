package io.paperagent.v2.contracts;

public record PlanStepId(String value) {
    public PlanStepId {
        value = Contracts.id(value, "planStepId");
    }
}
