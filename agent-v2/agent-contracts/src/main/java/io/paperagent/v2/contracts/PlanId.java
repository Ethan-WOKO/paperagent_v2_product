package io.paperagent.v2.contracts;

public record PlanId(String value) {
    public PlanId {
        value = Contracts.id(value, "planId");
    }
}
