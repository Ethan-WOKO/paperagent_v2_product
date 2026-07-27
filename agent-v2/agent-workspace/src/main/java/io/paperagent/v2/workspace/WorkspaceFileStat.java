package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;

public record WorkspaceFileStat(ProjectPath path, long size, ContentHash hash) {
    public WorkspaceFileStat {
        WorkspaceValues.require(path, "fileStat");
        WorkspaceValues.require(hash, "fileStat");
        if (size < 0) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_LIMIT, "fileStat", path);
        }
    }
}
