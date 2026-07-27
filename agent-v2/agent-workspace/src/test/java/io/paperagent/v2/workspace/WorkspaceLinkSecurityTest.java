package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.dataRoot;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.materialize;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.onlyContainer;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.provider;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLinkSecurityTest {
    @TempDir
    Path root;

    @Test
    void activeReplayAndInspectRejectNestedSymbolicLinkWithoutWorkOrExternalMutation()
            throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Path sentinel = outside.resolve("sentinel.txt");
        Files.writeString(sentinel, "preserve");
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> {
                    loads.incrementAndGet();
                    return snapshot(file("inside.txt", "inside"));
                },
                (target, content, options) -> {
                    writes.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                target -> {
                    deletes.incrementAndGet();
                    LocalWorkspaceProvider.deleteTree(target);
                });
        var materializationSpec = spec("active-nested-link");
        WorkspaceRef workspace = provider.materialize(materializationSpec).workspace();
        Path link = dataRoot(providerRoot).resolve("external-link");
        createRequiredSymbolicLink(link, outside);
        int writesAfterSuccess = writes.get();

        assertOpaqueLinkEscape(
                () -> provider.materialize(materializationSpec),
                outside);
        assertOpaqueLinkEscape(
                () -> provider.inspectMaterialization(materializationSpec),
                outside);

        assertEquals(1, loads.get());
        assertEquals(writesAfterSuccess, writes.get());
        assertEquals(1, publishes.get());
        assertEquals(0, deletes.get());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("preserve", Files.readString(sentinel));
        assertTrue(Files.exists(sentinel));

        Files.delete(link);
        provider.cleanup(workspace);
    }

    @Test
    void realSymbolicLinkEscapeIsRejectedForEveryFilesystemBoundary() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "outside");
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "inside")));
        WorkspaceRef workspace = materialize(provider, "link-boundary");
        Path link = dataRoot(providerRoot).resolve("link");
        createRequiredSymbolicLink(link, outside);

        assertLinkEscape(() -> provider.read(workspace, new ProjectPath("link/secret.txt")));
        assertLinkEscape(() -> provider.create(
                workspace,
                new ProjectPath("link/new.txt"),
                "new".getBytes(StandardCharsets.UTF_8)));
        assertLinkEscape(() -> provider.delete(workspace, new ProjectPath("link/secret.txt")));
        assertLinkEscape(() -> provider.move(
                workspace,
                new ProjectPath("inside.txt"),
                new ProjectPath("link/moved.txt")));
        assertLinkEscape(() -> provider.move(
                workspace,
                new ProjectPath("link/secret.txt"),
                new ProjectPath("stolen.txt")));
        assertLinkEscape(() -> provider.diff(
                workspace,
                new DiffId("link-diff"),
                Instant.EPOCH));
        assertLinkEscape(() -> provider.cleanup(workspace));
        assertEquals("outside", Files.readString(outsideFile));
        assertTrue(Files.exists(outsideFile));

        Files.delete(link);
        provider.cleanup(workspace);
    }

    @Test
    void cleanupRejectsWorkspaceContainerRedirectedOutsideProviderRoot() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Path parked = root.resolve("parked");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "inside")));
        WorkspaceRef workspace = materialize(provider, "cleanup-escape");
        Path container = onlyContainer(providerRoot);
        Files.move(container, parked);
        createRequiredSymbolicLink(container, outside);

        assertLinkEscape(() -> provider.cleanup(workspace));
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));

        Files.delete(container);
        Files.move(parked, container);
        provider.cleanup(workspace);
    }

    @Test
    void backupReadRejectsTargetSwappedToRealSymbolicLink() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        ProjectVersionSnapshot snapshot = snapshot(file("inside.txt", "inside"));
        AtomicBoolean swapped = new AtomicBoolean();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> snapshot,
                (source, maximum, operation, projectPath) -> {
                    Files.delete(source);
                    Files.createSymbolicLink(source, outsideFile);
                    swapped.set(true);
                    return LocalWorkspaceProvider.readBoundedNoFollow(
                            source,
                            maximum,
                            operation,
                            projectPath);
                });
        WorkspaceRef workspace = materialize(provider, "backup-link-swap");
        Path target = dataRoot(providerRoot).resolve("inside.txt");

        assertLinkEscape(() -> provider.replace(
                workspace,
                new ProjectPath("inside.txt"),
                "replacement".getBytes(StandardCharsets.UTF_8)));

        assertTrue(swapped.get());
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("outside", Files.readString(outsideFile));
        assertDirectoryEmpty(onlyContainer(providerRoot).resolve("staging"));

        Files.delete(target);
        Files.writeString(target, "inside");
        provider.cleanup(workspace);
    }

    @Test
    void failedReplaceRestoresBackupByReplacingSwappedLinkEntry() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        ProjectVersionSnapshot snapshot = snapshot(file("inside.txt", "before"));
        AtomicBoolean moveAttempted = new AtomicBoolean();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> snapshot,
                (source, target, replace) -> {
                    moveAttempted.set(true);
                    Files.delete(target);
                    Files.createSymbolicLink(target, outsideFile);
                    throw new IOException("forced replace failure after target swap");
                });
        WorkspaceRef workspace = materialize(provider, "restore-link-swap");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> provider.replace(
                        workspace,
                        new ProjectPath("inside.txt"),
                        "after".getBytes(StandardCharsets.UTF_8)));

        assertEquals(WorkspaceErrorCode.IO_FAILURE, failure.code());
        assertTrue(moveAttempted.get());
        Path target = dataRoot(providerRoot).resolve("inside.txt");
        assertFalse(Files.isSymbolicLink(target));
        assertBytes("before", provider.read(workspace, new ProjectPath("inside.txt")));
        assertEquals("outside", Files.readString(outsideFile));
        assertDirectoryEmpty(onlyContainer(providerRoot).resolve("staging"));
        provider.cleanup(workspace);
    }

    @Test
    void replaceRejectsOccupiedBackupLinkWithoutFollowingIt() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "before")));
        WorkspaceRef workspace = materialize(provider, "occupied-backup-link");
        Path staging = onlyContainer(providerRoot).resolve("staging");
        Path backup = staging.resolve(
                WorkspaceHashes.sha256Text("inside.txt") + ".bak");
        Files.createSymbolicLink(backup, outsideFile);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> provider.replace(
                        workspace,
                        new ProjectPath("inside.txt"),
                        "after".getBytes(StandardCharsets.UTF_8)));

        assertEquals(WorkspaceErrorCode.TEMPORARY_PATH_OCCUPIED, failure.code());
        assertTrue(Files.isSymbolicLink(backup));
        assertEquals("outside", Files.readString(outsideFile));
        assertBytes("before", provider.read(workspace, new ProjectPath("inside.txt")));

        Files.delete(backup);
        provider.cleanup(workspace);
    }

    @Test
    void failedPendingCleanupRetryDeletesLinkEntryWithoutTouchingTarget()
            throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        AtomicInteger deleteAttempts = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> snapshot(file("inside.txt", "inside")),
                (target, content, options) -> Files.createSymbolicLink(target, outsideFile),
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    if (deleteAttempts.incrementAndGet() == 1) {
                        Files.createSymbolicLink(
                                target.resolve("data/retry-link"),
                                outsideFile);
                        throw new IOException("forced pending cleanup failure");
                    }
                    LocalWorkspaceProvider.deleteTree(target);
                });
        var materializationSpec = spec("materialization-link-swap");
        WorkspaceRef workspace = new WorkspaceRef(
                materializationSpec.workspaceId(),
                materializationSpec.sourceProjectVersion());

        assertLinkEscape(() -> provider.materialize(materializationSpec));

        Path pending = providerRoot.resolve(
                "pending-" + WorkspaceHashes.sha256Text(
                        materializationSpec.workspaceId().value()));
        Path retryLink = pending.resolve("data/retry-link");
        assertEquals(1, deleteAttempts.get());
        assertTrue(Files.isSymbolicLink(retryLink));
        WorkspaceException partial = assertThrows(
                WorkspaceException.class,
                () -> provider.inspectMaterialization(materializationSpec));
        assertEquals(WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE, partial.code());
        assertEquals("outside", Files.readString(outsideFile));
        assertTrue(Files.exists(outsideFile));

        provider.cleanup(workspace);

        assertEquals(2, deleteAttempts.get());
        assertFalse(Files.exists(pending, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("outside", Files.readString(outsideFile));
        assertTrue(Files.exists(outsideFile));
        try (var entries = Files.list(providerRoot)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void unknownFinalAndPendingLinksAreNeverAdoptedOrDeleted() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outside);
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "inside")));

        for (String prefix : java.util.List.of("ws-", "pending-")) {
            var materializationSpec = spec("unknown-link-" + prefix);
            Path occupied = providerRoot.resolve(
                    prefix + WorkspaceHashes.sha256Text(
                            materializationSpec.workspaceId().value()));
            Files.createSymbolicLink(occupied, outside);
            assertLinkEscape(() -> provider.materialize(materializationSpec));
            assertLinkEscape(() -> provider.inspectMaterialization(materializationSpec));
            assertLinkEscape(() -> provider.cleanup(new WorkspaceRef(
                    materializationSpec.workspaceId(),
                    materializationSpec.sourceProjectVersion())));
            assertTrue(Files.isSymbolicLink(occupied));
            assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
            Files.delete(occupied);
        }
    }

    @Test
    void windowsJunctionInsideWorkspaceIsRejectedWhenHostSupportsIt() throws Exception {
        Assumptions.assumeTrue(isWindows(), "Windows-only junction/reparse coverage");
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "inside")));
        var materializationSpec = spec("junction-boundary");
        WorkspaceRef workspace = provider.materialize(materializationSpec).workspace();
        Path junction = dataRoot(providerRoot).resolve("junction");
        Process process = new ProcessBuilder(
                "cmd",
                "/c",
                "mklink",
                "/J",
                junction.toString(),
                outside.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        Assumptions.assumeTrue(
                exit == 0 && Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "Windows host cannot create a junction: " + output);

        assertLinkEscape(() -> provider.materialize(materializationSpec));
        assertLinkEscape(() -> provider.inspectMaterialization(materializationSpec));
        assertLinkEscape(() -> provider.list(workspace));
        assertLinkEscape(() -> provider.cleanup(workspace));
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));

        Files.delete(junction);
        provider.cleanup(workspace);
    }

    private static void createRequiredSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            if (isWindows()) {
                Assumptions.assumeTrue(
                        false,
                        "Windows test host cannot create symbolic links; Linux CI must execute this test");
            }
            throw exception;
        }
    }

    private static void requireSymbolicLinkSupport(Path probe, Path target) throws IOException {
        createRequiredSymbolicLink(probe, target);
        Files.delete(probe);
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void assertLinkEscape(Runnable operation) {
        WorkspaceException exception = assertThrows(WorkspaceException.class, operation::run);
        assertEquals(WorkspaceErrorCode.LINK_ESCAPE, exception.code());
    }

    private static void assertOpaqueLinkEscape(
            Runnable operation,
            Path sensitivePath) {
        WorkspaceException exception =
                assertThrows(WorkspaceException.class, operation::run);
        assertEquals(WorkspaceErrorCode.LINK_ESCAPE, exception.code());
        assertTrue(exception.projectPath().isEmpty());
        assertFalse(exception.getMessage().contains(sensitivePath.toString()));
        assertFalse(exception.getMessage().contains(sensitivePath.getFileName().toString()));
    }
}
