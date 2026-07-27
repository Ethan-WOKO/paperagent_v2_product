package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.materialize;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.provider;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceProviderJourneyTest {
    @TempDir
    Path root;

    @Test
    void materializesEditsDiffsAndCleansWithoutChangingSource() throws Exception {
        ProjectFileSnapshot alpha = file("alpha.txt", "alpha");
        ProjectFileSnapshot nested = file("docs/readme.md", "readme");
        ProjectVersionSnapshot source = snapshot(nested, alpha);
        byte[] sourceBefore = alpha.content();
        LocalWorkspaceProvider provider = provider(root, source);

        WorkspaceRef workspace = materialize(provider, "workspace-a");

        assertBytes("alpha", provider.read(workspace, new ProjectPath("alpha.txt")));
        assertBytes("readme", provider.read(workspace, new ProjectPath("docs/readme.md")));
        assertEquals(
                List.of("alpha.txt", "docs/readme.md"),
                provider.list(workspace).stream().map(stat -> stat.path().value()).toList());
        assertEquals(5, provider.stat(workspace, new ProjectPath("alpha.txt")).size());

        byte[] read = provider.read(workspace, new ProjectPath("alpha.txt"));
        read[0] = 'X';
        assertBytes("alpha", provider.read(workspace, new ProjectPath("alpha.txt")));

        byte[] replacement = "changed".getBytes(StandardCharsets.UTF_8);
        provider.replace(workspace, new ProjectPath("alpha.txt"), replacement);
        replacement[0] = 'X';
        provider.create(workspace, new ProjectPath("new.txt"), "new".getBytes(StandardCharsets.UTF_8));
        provider.move(workspace, new ProjectPath("docs/readme.md"), new ProjectPath("README.md"));

        WorkspaceDiff diff = provider.diff(
                workspace,
                new DiffId("diff-1"),
                Instant.parse("2026-07-24T00:00:00Z"));
        assertEquals(
                List.of("alpha.txt", "docs/readme.md", "new.txt"),
                diff.entries().stream().map(entry -> entry.path().value()).toList());
        assertEquals(
                List.of(DiffKind.MODIFY, DiffKind.RENAME, DiffKind.ADD),
                diff.entries().stream().map(entry -> entry.kind()).toList());
        assertEquals(
                "README.md",
                diff.entries().get(1).targetPath().orElseThrow().value());
        assertBytes("changed", provider.read(workspace, new ProjectPath("alpha.txt")));

        provider.delete(workspace, new ProjectPath("new.txt"));
        assertFalse(provider.list(workspace).stream()
                .anyMatch(stat -> stat.path().equals(new ProjectPath("new.txt"))));

        assertArrayEquals(sourceBefore, alpha.content());
        WorkspaceTestSupport.assertSnapshotContent(alpha, "alpha");
        assertThrows(UnsupportedOperationException.class, () -> source.files().clear());

        provider.cleanup(workspace);
        provider.cleanup(workspace);
        try (var children = java.nio.file.Files.list(root)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void isolatesWorkspacesWithTheSameRelativePath() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("same.txt", "original")));
        WorkspaceRef first = materialize(provider, "workspace-first");
        WorkspaceRef second = materialize(provider, "workspace-second");

        provider.replace(first, new ProjectPath("same.txt"), "first".getBytes(StandardCharsets.UTF_8));

        assertBytes("first", provider.read(first, new ProjectPath("same.txt")));
        assertBytes("original", provider.read(second, new ProjectPath("same.txt")));
    }
}
