package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceDiffEntry;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.materialize;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.provider;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceDiffTest {
    @TempDir
    Path root;

    @Test
    void reportsAddModifyDeleteAndUnambiguousRenameInPathOrder() {
        LocalWorkspaceProvider provider = provider(root, snapshot(
                file("delete.txt", "delete"),
                file("modify.txt", "before"),
                file("rename.txt", "rename"),
                file("unchanged.txt", "same")));
        WorkspaceRef workspace = materialize(provider, "diff-workspace");

        provider.delete(workspace, new ProjectPath("delete.txt"));
        provider.replace(workspace, new ProjectPath("modify.txt"), bytes("after"));
        provider.move(workspace, new ProjectPath("rename.txt"), new ProjectPath("moved.txt"));
        provider.create(workspace, new ProjectPath("added.txt"), bytes("added"));

        List<WorkspaceDiffEntry> entries = provider.diff(
                workspace,
                new DiffId("diff-complete"),
                Instant.EPOCH).entries();

        assertEquals(
                List.of("added.txt", "delete.txt", "modify.txt", "rename.txt"),
                entries.stream().map(item -> item.path().value()).toList());
        assertEquals(
                List.of(DiffKind.ADD, DiffKind.DELETE, DiffKind.MODIFY, DiffKind.RENAME),
                entries.stream().map(WorkspaceDiffEntry::kind).toList());
        assertEquals("moved.txt", entries.get(3).targetPath().orElseThrow().value());
        assertTrue(entries.stream().noneMatch(item -> item.path().value().equals("unchanged.txt")));
    }

    @Test
    void ambiguousSameHashMovesRemainDeletesAndAdds() {
        LocalWorkspaceProvider provider = provider(root, snapshot(
                file("old-a.txt", "same"),
                file("old-b.txt", "same")));
        WorkspaceRef workspace = materialize(provider, "ambiguous-workspace");
        provider.move(workspace, new ProjectPath("old-a.txt"), new ProjectPath("new-a.txt"));
        provider.move(workspace, new ProjectPath("old-b.txt"), new ProjectPath("new-b.txt"));

        List<WorkspaceDiffEntry> entries = provider.diff(
                workspace,
                new DiffId("diff-ambiguous"),
                Instant.EPOCH).entries();

        assertEquals(4, entries.size());
        assertEquals(2, entries.stream().filter(item -> item.kind() == DiffKind.ADD).count());
        assertEquals(2, entries.stream().filter(item -> item.kind() == DiffKind.DELETE).count());
        assertTrue(entries.stream().noneMatch(item -> item.kind() == DiffKind.RENAME));
    }

    @Test
    void movedAndModifiedFileIsDeletePlusAdd() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("old.txt", "before")));
        WorkspaceRef workspace = materialize(provider, "modified-move-workspace");
        provider.move(workspace, new ProjectPath("old.txt"), new ProjectPath("new.txt"));
        provider.replace(workspace, new ProjectPath("new.txt"), bytes("after"));

        List<DiffKind> kinds = provider.diff(
                        workspace,
                        new DiffId("diff-modified-move"),
                        Instant.EPOCH)
                .entries().stream().map(WorkspaceDiffEntry::kind).toList();

        assertEquals(List.of(DiffKind.ADD, DiffKind.DELETE), kinds);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
