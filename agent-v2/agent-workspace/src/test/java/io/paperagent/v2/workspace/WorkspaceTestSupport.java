package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorkspaceTestSupport {
    static final ProjectVersionRef VERSION = new ProjectVersionRef("project-1", "version-1");
    static final WorkspaceMaterializationLimits GENEROUS_LIMITS =
            new WorkspaceMaterializationLimits(1024, 8192, 32);

    private WorkspaceTestSupport() {
    }

    static ProjectFileSnapshot file(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ProjectFileSnapshot(
                new ProjectPath(path),
                bytes,
                WorkspaceHashes.sha256(bytes),
                Map.of("mediaType", "text/plain"));
    }

    static ProjectVersionSnapshot snapshot(ProjectFileSnapshot... files) {
        return new ProjectVersionSnapshot(VERSION, List.of(files), Map.of("kind", "test"));
    }

    static LocalWorkspaceProvider provider(Path root, ProjectVersionSnapshot snapshot) {
        return new LocalWorkspaceProvider(root, requested -> {
            assertEquals(VERSION, requested);
            return snapshot;
        });
    }

    static WorkspaceRef materialize(
            LocalWorkspaceProvider provider,
            String workspaceId,
            WorkspaceMaterializationLimits limits) {
        return provider.materialize(spec(workspaceId, VERSION, limits)).workspace();
    }

    static WorkspaceRef materialize(LocalWorkspaceProvider provider, String workspaceId) {
        return materialize(provider, workspaceId, GENEROUS_LIMITS);
    }

    static WorkspaceMaterializationSpec spec(String workspaceId) {
        return spec(workspaceId, VERSION, GENEROUS_LIMITS);
    }

    static WorkspaceMaterializationSpec spec(
            String workspaceId,
            ProjectVersionRef source,
            WorkspaceMaterializationLimits limits) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId(workspaceId),
                source,
                limits);
    }

    static Path onlyContainer(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.findFirst().orElseThrow();
        }
    }

    static Path dataRoot(Path root) throws IOException {
        return onlyContainer(root).resolve("data");
    }

    static void assertBytes(String expected, byte[] actual) {
        assertEquals(expected, new String(actual, StandardCharsets.UTF_8));
    }

    static void assertSnapshotContent(ProjectFileSnapshot file, String expected) {
        assertBytes(expected, file.content());
        byte[] first = file.content();
        Arrays.fill(first, (byte) 'x');
        assertBytes(expected, file.content());
    }
}
