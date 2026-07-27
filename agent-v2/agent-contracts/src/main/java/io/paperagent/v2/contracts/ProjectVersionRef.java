package io.paperagent.v2.contracts;

public record ProjectVersionRef(String projectId, String versionId) {
    public ProjectVersionRef {
        projectId = Contracts.id(projectId, "projectVersion.projectId");
        versionId = Contracts.id(versionId, "projectVersion.versionId");
    }
}
