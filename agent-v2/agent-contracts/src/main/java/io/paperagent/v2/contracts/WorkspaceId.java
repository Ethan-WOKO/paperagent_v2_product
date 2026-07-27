package io.paperagent.v2.contracts;

public record WorkspaceId(String value) {
    public WorkspaceId {
        value = Contracts.id(value, "workspaceId");
    }
}
