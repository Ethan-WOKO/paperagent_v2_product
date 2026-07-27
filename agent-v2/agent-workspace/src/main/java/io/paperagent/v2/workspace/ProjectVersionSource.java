package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ProjectVersionRef;

@FunctionalInterface
public interface ProjectVersionSource {
    ProjectVersionSnapshot load(ProjectVersionRef version);
}
