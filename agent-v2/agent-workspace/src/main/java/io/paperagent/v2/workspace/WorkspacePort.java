package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;

import java.time.Instant;
import java.util.List;

public interface WorkspacePort {
    VerifiedWorkspaceMaterialization materialize(
            WorkspaceMaterializationSpec spec);

    VerifiedWorkspaceMaterialization inspectMaterialization(
            WorkspaceMaterializationSpec spec);

    List<WorkspaceFileStat> list(WorkspaceRef workspace);

    WorkspaceFileStat stat(WorkspaceRef workspace, ProjectPath path);

    byte[] read(WorkspaceRef workspace, ProjectPath path);

    void create(WorkspaceRef workspace, ProjectPath path, byte[] content);

    void replace(WorkspaceRef workspace, ProjectPath path, byte[] content);

    void delete(WorkspaceRef workspace, ProjectPath path);

    void move(WorkspaceRef workspace, ProjectPath source, ProjectPath target);

    WorkspaceDiff diff(WorkspaceRef workspace, DiffId diffId, Instant createdAt);

    void cleanup(WorkspaceRef workspace);
}
