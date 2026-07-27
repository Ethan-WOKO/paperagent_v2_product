package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable source file. Content and metadata are defensively copied.
 */
public final class ProjectFileSnapshot {
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_KEY_LENGTH = 128;
    private static final int MAX_METADATA_VALUE_LENGTH = 1024;

    private final ProjectPath path;
    private final byte[] content;
    private final ContentHash hash;
    private final Map<String, String> metadata;

    public ProjectFileSnapshot(
            ProjectPath path,
            byte[] content,
            ContentHash hash,
            Map<String, String> metadata) {
        this.path = WorkspaceValues.require(path, "snapshotFile");
        this.content = WorkspaceValues.require(content, "snapshotFile").clone();
        this.hash = WorkspaceValues.require(hash, "snapshotFile");
        this.metadata = copyMetadata(metadata, "snapshotFile");
    }

    public ProjectPath path() {
        return path;
    }

    public byte[] content() {
        return content.clone();
    }

    public ContentHash hash() {
        return hash;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    private static Map<String, String> copyMetadata(Map<String, String> source, String operation) {
        WorkspaceValues.require(source, operation);
        if (source.size() > MAX_METADATA_ENTRIES) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_METADATA, operation);
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > MAX_METADATA_KEY_LENGTH
                    || value == null || value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new WorkspaceException(WorkspaceErrorCode.INVALID_METADATA, operation);
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
