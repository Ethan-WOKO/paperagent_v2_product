package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ProjectVersionRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable source manifest returned by a ProjectVersionSource.
 */
public final class ProjectVersionSnapshot {
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_KEY_LENGTH = 128;
    private static final int MAX_METADATA_VALUE_LENGTH = 1024;

    private final ProjectVersionRef version;
    private final List<ProjectFileSnapshot> files;
    private final Map<String, String> metadata;

    public ProjectVersionSnapshot(
            ProjectVersionRef version,
            List<ProjectFileSnapshot> files,
            Map<String, String> metadata) {
        this.version = WorkspaceValues.require(version, "snapshot");
        WorkspaceValues.require(files, "snapshot");
        if (files.stream().anyMatch(file -> file == null)) {
            throw new WorkspaceException(WorkspaceErrorCode.REQUIRED_VALUE_MISSING, "snapshot");
        }
        this.files = List.copyOf(new ArrayList<>(files));
        this.metadata = copyMetadata(metadata);
    }

    public ProjectVersionRef version() {
        return version;
    }

    public List<ProjectFileSnapshot> files() {
        return files;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        WorkspaceValues.require(source, "snapshot");
        if (source.size() > MAX_METADATA_ENTRIES) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_METADATA, "snapshot");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > MAX_METADATA_KEY_LENGTH
                    || value == null || value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new WorkspaceException(WorkspaceErrorCode.INVALID_METADATA, "snapshot");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
