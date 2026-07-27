package io.paperagent.v2.contracts;

public record WorkspaceRef(WorkspaceId id, ProjectVersionRef sourceProjectVersion) {
    public WorkspaceRef {
        Contracts.required(id, "workspace.id");
        Contracts.required(sourceProjectVersion, "workspace.sourceProjectVersion");
    }
}
