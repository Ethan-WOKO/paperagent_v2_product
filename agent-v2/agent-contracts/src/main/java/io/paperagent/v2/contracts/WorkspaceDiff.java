package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;

public record WorkspaceDiff(
        DiffId id,
        WorkspaceRef workspace,
        List<WorkspaceDiffEntry> entries,
        Instant createdAt) {

    public WorkspaceDiff {
        Contracts.required(id, "workspaceDiff.id");
        Contracts.required(workspace, "workspaceDiff.workspace");
        entries = Contracts.list(entries, "workspaceDiff.entries");
        Contracts.required(createdAt, "workspaceDiff.createdAt");
    }
}
