package io.paperagent.v2.contracts;

public record PlanRevisionId(String value) {
    public PlanRevisionId {
        value = Contracts.id(value, "planRevisionId");
    }
}
