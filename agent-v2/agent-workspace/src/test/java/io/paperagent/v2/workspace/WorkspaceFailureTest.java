package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.materialize;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.onlyContainer;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.provider;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFailureTest {
    @TempDir
    Path root;

    @Test
    void rejectsHashMismatchBeforeCreatingWorkspace() throws Exception {
        ProjectFileSnapshot invalid = new ProjectFileSnapshot(
                new ProjectPath("bad.txt"),
                bytes("actual"),
                WorkspaceHashes.sha256(bytes("declared")),
                Map.of());
        LocalWorkspaceProvider provider = provider(root, snapshot(invalid));

        assertCode(
                WorkspaceErrorCode.HASH_MISMATCH,
                () -> materialize(provider, "hash-mismatch"));
        assertRootEmpty();
    }

    @Test
    void rejectsDuplicateAndPrefixCollisionsAndLeavesNoPartialWorkspace() throws Exception {
        assertMaterializationFailure(
                WorkspaceErrorCode.DUPLICATE_PATH,
                snapshot(file("same.txt", "one"), file("same.txt", "two")),
                "duplicate");
        assertMaterializationFailure(
                WorkspaceErrorCode.PATH_COLLISION,
                snapshot(file("parent", "file"), file("parent/child.txt", "child")),
                "prefix");
    }

    @Test
    void partialMaterializationFailureRemovesEveryCreatedFileAndDirectory() throws Exception {
        ProjectVersionSnapshot snapshot = snapshot(
                file("a-first.txt", "first"),
                file("b-second.txt", "second"));
        AtomicInteger writes = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot,
                (source, target, replace) -> {
                    throw new AssertionError("materialization must not use the move strategy");
                },
                (target, content, options) -> {
                    if (writes.incrementAndGet() == 2) {
                        throw new IOException("forced second-file failure");
                    }
                    Files.write(target, content, options);
                });

        assertCode(
                WorkspaceErrorCode.IO_FAILURE,
                () -> materialize(provider, "partial-materialization"));
        assertEquals(2, writes.get());
        assertRootEmpty();
    }

    @Test
    void throwingAbsenceProbeFailsClosedWithoutEscapingRuntime() {
        assertFalse(LocalWorkspaceProvider.noThrowAbsenceProbe(() -> {
            throw new SecurityException("raw probe failure");
        }));
        assertFalse(LocalWorkspaceProvider.noThrowAbsenceProbe(() -> {
            throw new IllegalStateException("raw runtime failure");
        }));
        assertTrue(LocalWorkspaceProvider.noThrowAbsenceProbe(() -> true));
    }

    @Test
    void zipProviderNoFollowUnsupportedMapsToOpaqueLinkEscape()
            throws Exception {
        Path archive = root.resolve("nofollow-provider.zip");
        URI archiveUri = URI.create("jar:" + archive.toUri());
        ProjectPath projectPath = new ProjectPath("entry.txt");
        try (FileSystem zip = FileSystems.newFileSystem(
                archiveUri,
                Map.of("create", "true"))) {
            Path entry = zip.getPath("/entry.txt");
            Files.writeString(entry, "content");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> {
                        try (var ignored = Files.newInputStream(
                                entry,
                                StandardOpenOption.READ,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            // Opening is expected to fail before an InputStream exists.
                        }
                    });

            WorkspaceException failure = assertThrows(
                    WorkspaceException.class,
                    () -> WorkspaceHashes.sha256(
                            entry,
                            1024,
                            "hashZipEntry",
                            projectPath));

            assertEquals(WorkspaceErrorCode.LINK_ESCAPE, failure.code());
            assertEquals("hashZipEntry", failure.operation());
            assertEquals(projectPath, failure.projectPath().orElseThrow());
            assertEquals(null, failure.getCause());
            assertFalse(failure.getMessage().contains(archive.toString()));
        }
    }

    @Test
    void pendingDirectoryIteratorFailuresMapToOpaqueVerificationFailure() {
        String sensitive = "sensitive host path";
        List<DirectoryStream<Path>> streams = List.of(
                new DirectoryStream<>() {
                    @Override
                    public Iterator<Path> iterator() {
                        throw new DirectoryIteratorException(
                                new IOException(sensitive));
                    }

                    @Override
                    public void close() {
                    }
                },
                new DirectoryStream<>() {
                    @Override
                    public Iterator<Path> iterator() {
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                throw new DirectoryIteratorException(
                                        new IOException(sensitive));
                            }

                            @Override
                            public Path next() {
                                throw new AssertionError(
                                        "next must not follow failed hasNext");
                            }
                        };
                    }

                    @Override
                    public void close() {
                    }
                });

        for (DirectoryStream<Path> stream : streams) {
            WorkspaceException failure = assertThrows(
                    WorkspaceException.class,
                    () -> WorkspaceMaterializationVerifier.visitPendingEntries(
                            stream,
                            ignored -> {
                            }));

            assertEquals(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    failure.code());
            assertEquals("materialize", failure.operation());
            assertTrue(failure.projectPath().isEmpty());
            assertEquals(null, failure.getCause());
            assertFalse(failure.getMessage().contains(sensitive));
        }
    }

    @Test
    void failedPendingCleanupRuntimeIsOpaqueAndRemainsRetryable() {
        AtomicInteger deleteAttempts = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot(file("paper.txt", "paper")),
                (target, content, options) -> {
                    throw new IOException("forced writer path " + target);
                },
                LocalWorkspaceProvider::defaultPublish,
                target -> {
                    int attempt = deleteAttempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new IllegalStateException(
                                "raw materialization cleanup path " + target);
                    }
                    if (attempt == 2) {
                        throw new IllegalStateException(
                                "raw cleanup path " + target);
                    }
                    LocalWorkspaceProvider.deleteTree(target);
                });
        WorkspaceMaterializationSpec spec = new WorkspaceMaterializationSpec(
                new WorkspaceId("runtime-cleanup-retry"),
                VERSION,
                WorkspaceTestSupport.GENEROUS_LIMITS);
        WorkspaceRef workspace = new WorkspaceRef(
                spec.workspaceId(),
                spec.sourceProjectVersion());

        WorkspaceException materializationFailure = assertThrows(
                WorkspaceException.class,
                () -> provider.materialize(spec));
        assertEquals(WorkspaceErrorCode.IO_FAILURE, materializationFailure.code());
        assertTrue(materializationFailure.projectPath().isEmpty());
        assertEquals(null, materializationFailure.getCause());
        assertFalse(materializationFailure.getMessage().contains(root.toString()));
        assertFalse(materializationFailure.getMessage().contains(
                "raw materialization cleanup path"));
        assertEquals(1, deleteAttempts.get());

        WorkspaceException cleanupFailure = assertThrows(
                WorkspaceException.class,
                () -> provider.cleanup(workspace));

        assertEquals(WorkspaceErrorCode.IO_FAILURE, cleanupFailure.code());
        assertTrue(cleanupFailure.projectPath().isEmpty());
        assertEquals(null, cleanupFailure.getCause());
        assertFalse(cleanupFailure.getMessage().contains(root.toString()));
        assertFalse(cleanupFailure.getMessage().contains("raw cleanup path"));
        assertEquals(2, deleteAttempts.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                () -> provider.materialize(spec));

        provider.cleanup(workspace);
        assertEquals(3, deleteAttempts.get());
        assertCode(
                WorkspaceErrorCode.WORKSPACE_RETIRED,
                () -> provider.materialize(spec));
    }

    @Test
    void appliesFileAggregateAndCountLimitsBeforeWriting() throws Exception {
        assertLimitFailure(
                WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                new WorkspaceMaterializationLimits(2, 100, 10),
                snapshot(file("too-big.txt", "abc")),
                "file-limit");
        assertLimitFailure(
                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                new WorkspaceMaterializationLimits(10, 3, 10),
                snapshot(file("a.txt", "aa"), file("b.txt", "bb")),
                "aggregate-limit");
        assertLimitFailure(
                WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED,
                new WorkspaceMaterializationLimits(10, 100, 1),
                snapshot(file("a.txt", "a"), file("b.txt", "b")),
                "count-limit");
    }

    @Test
    void rejectsWriteGrowthBeforeChangingPriorContent() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "12")));
        WorkspaceRef workspace = materialize(
                provider,
                "write-limit",
                new WorkspaceMaterializationLimits(3, 3, 2));

        assertCode(
                WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                () -> provider.replace(workspace, new ProjectPath("base.txt"), bytes("1234")));
        assertCode(
                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                () -> provider.create(workspace, new ProjectPath("new.txt"), bytes("12")));
        assertBytes("12", provider.read(workspace, new ProjectPath("base.txt")));
        assertEquals(1, provider.list(workspace).size());
    }

    @Test
    void failedReplaceFallbackPreservesPriorFileAndRemovesStagingArtifacts() throws Exception {
        ProjectVersionSnapshot snapshot = snapshot(file("base.txt", "before"));
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot,
                (source, target, replace) -> {
                    throw new IOException("forced failure");
                });
        WorkspaceRef workspace = materialize(provider, "fallback-failure");

        assertCode(
                WorkspaceErrorCode.IO_FAILURE,
                () -> provider.replace(workspace, new ProjectPath("base.txt"), bytes("after")));
        assertBytes("before", provider.read(workspace, new ProjectPath("base.txt")));
        assertDirectoryEmpty(onlyContainer(root).resolve("staging"));
    }

    @Test
    void boundedBackupReadRejectsGrowthWithoutApplyingReplacement() throws Exception {
        ProjectVersionSnapshot snapshot = snapshot(file("base.txt", "before"));
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot,
                (source, maximum, operation, projectPath) -> {
                    Files.write(source, bytes("!"), java.nio.file.StandardOpenOption.APPEND);
                    return LocalWorkspaceProvider.readBoundedNoFollow(
                            source,
                            maximum,
                            operation,
                            projectPath);
                });
        WorkspaceRef workspace = materialize(
                provider,
                "bounded-backup",
                new WorkspaceMaterializationLimits(6, 64, 2));
        Path dataFile = onlyContainer(root).resolve("data").resolve("base.txt");

        assertCode(
                WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                () -> provider.replace(workspace, new ProjectPath("base.txt"), bytes("after")));

        assertEquals("before!", Files.readString(dataFile));
        assertDirectoryEmpty(onlyContainer(root).resolve("staging"));
    }

    @Test
    void rejectsUnknownWorkspaceMismatchedReferenceAndInvalidOperationShapes() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "base")));
        WorkspaceRef unknown = new WorkspaceRef(new WorkspaceId("unknown"), VERSION);
        assertCode(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, () -> provider.list(unknown));

        WorkspaceRef workspace = materialize(provider, "known");
        WorkspaceRef wrongSource = new WorkspaceRef(
                workspace.id(),
                new ProjectVersionRef("project-1", "other-version"));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                () -> provider.list(wrongSource));
        assertCode(
                WorkspaceErrorCode.PATH_ALREADY_EXISTS,
                () -> provider.create(workspace, new ProjectPath("base.txt"), bytes("new")));
        assertCode(
                WorkspaceErrorCode.PATH_NOT_FOUND,
                () -> provider.replace(workspace, new ProjectPath("missing.txt"), bytes("new")));
        assertCode(
                WorkspaceErrorCode.PATH_NOT_FOUND,
                () -> provider.delete(workspace, new ProjectPath("missing.txt")));
    }

    @Test
    void sourceFailuresAndReferenceMismatchAreStable() {
        AtomicInteger deletes = new AtomicInteger();
        WorkspaceTreeDeleter deleter = target -> {
            deletes.incrementAndGet();
            LocalWorkspaceProvider.deleteTree(target);
        };
        LocalWorkspaceProvider failing = new LocalWorkspaceProvider(
                root,
                ignored -> {
                    throw new IllegalStateException("source details");
                },
                Files::write,
                LocalWorkspaceProvider::defaultPublish,
                deleter);
        assertCode(
                WorkspaceErrorCode.SOURCE_FAILURE,
                () -> failing.materialize(new WorkspaceMaterializationSpec(
                        new WorkspaceId("source-failure"),
                        VERSION,
                        WorkspaceTestSupport.GENEROUS_LIMITS)));

        ProjectVersionSnapshot wrong = new ProjectVersionSnapshot(
                new ProjectVersionRef("project-1", "wrong"),
                List.of(),
                Map.of());
        LocalWorkspaceProvider mismatch = new LocalWorkspaceProvider(
                root,
                ignored -> wrong,
                Files::write,
                LocalWorkspaceProvider::defaultPublish,
                deleter);
        assertCode(
                WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH,
                () -> mismatch.materialize(new WorkspaceMaterializationSpec(
                        new WorkspaceId("source-mismatch"),
                        VERSION,
                        WorkspaceTestSupport.GENEROUS_LIMITS)));
        assertEquals(0, deletes.get());
    }

    @Test
    void caseFoldCollisionIsRejectedWhenFilesystemIsCaseInsensitive() throws Exception {
        boolean caseSensitive = probeCaseSensitivity(root);
        LocalWorkspaceProvider provider = provider(root, snapshot(
                file("Readme.md", "one"),
                file("README.md", "two")));

        if (caseSensitive) {
            WorkspaceRef workspace = materialize(provider, "case-sensitive");
            assertEquals(2, provider.list(workspace).size());
        } else {
            assertCode(
                    WorkspaceErrorCode.PATH_COLLISION,
                    () -> materialize(provider, "case-insensitive"));
            assertRootEmpty();
        }
    }

    @Test
    void constructorCaseProbePreservesPreexistingUppercaseSentinel()
            throws Exception {
        Path providerRoot = root.resolve("shared-provider");
        Files.createDirectories(providerRoot);
        Assumptions.assumeTrue(
                probeCaseSensitivity(providerRoot),
                "Requires a genuinely case-sensitive filesystem; Ubuntu CI must execute");
        Path sentinel = providerRoot.resolve(".PAPERAGENT-CASE-PROBE");
        byte[] sentinelBytes = bytes("preserve-unknown-entry");
        Files.write(sentinel, sentinelBytes, StandardOpenOption.CREATE_NEW);

        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(
                        file("Readme.md", "first"),
                        file("README.md", "second")));

        assertBytes("preserve-unknown-entry", Files.readAllBytes(sentinel));
        assertFalse(Files.exists(
                providerRoot.resolve(".paperagent-case-probe")));

        WorkspaceRef workspace = materialize(provider, "case-probe-ownership");
        assertEquals(2, provider.list(workspace).size());
        assertBytes(
                "first",
                provider.read(workspace, new ProjectPath("Readme.md")));
        assertBytes(
                "second",
                provider.read(workspace, new ProjectPath("README.md")));

        provider.cleanup(workspace);

        assertArrayEquals(sentinelBytes, Files.readAllBytes(sentinel));
        assertFalse(Files.exists(
                providerRoot.resolve(".paperagent-case-probe")));
        try (var entries = Files.list(providerRoot)) {
            assertEquals(List.of(sentinel), entries.toList());
        }
    }

    @Test
    void snapshotsAndMetadataAreDefensivelyCopied() {
        byte[] bytes = bytes("safe");
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("type", "text");
        ProjectFileSnapshot file = new ProjectFileSnapshot(
                new ProjectPath("file.txt"),
                bytes,
                WorkspaceHashes.sha256(bytes),
                metadata);
        List<ProjectFileSnapshot> files = new ArrayList<>();
        files.add(file);
        ProjectVersionSnapshot snapshot = new ProjectVersionSnapshot(VERSION, files, metadata);

        bytes[0] = 'X';
        metadata.put("type", "changed");
        files.clear();

        assertBytes("safe", file.content());
        assertEquals("text", file.metadata().get("type"));
        assertEquals(1, snapshot.files().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.metadata().put("new", "value"));
    }

    @Test
    void projectPathContractStillRejectsAbsoluteAndTraversalForms() {
        for (String invalid : List.of(
                "/absolute.txt",
                "\\absolute.txt",
                "C:/absolute.txt",
                "../escape.txt",
                "nested/../escape.txt",
                "nested\\escape.txt")) {
            ContractViolationException exception = assertThrows(
                    ContractViolationException.class,
                    () -> new ProjectPath(invalid));
            assertEquals(ViolationCode.INVALID_PATH, exception.violations().get(0).code());
        }
    }

    @Test
    void providerRejectsHostSpecificAliasPaths() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "base")));
        WorkspaceRef workspace = materialize(provider, "portable-path");

        assertCode(
                WorkspaceErrorCode.PATH_COLLISION,
                () -> provider.create(workspace, new ProjectPath("stream:name"), bytes("bad")));
        assertCode(
                WorkspaceErrorCode.PATH_COLLISION,
                () -> provider.create(workspace, new ProjectPath("CON.txt"), bytes("bad")));
    }

    @Test
    void exceptionDoesNotExposeConfiguredHostPathOrIoCause() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "base")));
        WorkspaceRef workspace = materialize(provider, "no-host-path");
        WorkspaceException exception = assertThrows(
                WorkspaceException.class,
                () -> provider.read(workspace, new ProjectPath("missing.txt")));

        assertFalse(exception.getMessage().contains(root.toString()));
        assertEquals(null, exception.getCause());
    }

    @Test
    void configuredProviderRootMustBeExplicitlyAbsolute() {
        assertCode(
                WorkspaceErrorCode.PATH_ESCAPE,
                () -> new LocalWorkspaceProvider(
                        Path.of("relative-workspaces"),
                        ignored -> snapshot()));
    }

    private void assertMaterializationFailure(
            WorkspaceErrorCode expected,
            ProjectVersionSnapshot snapshot,
            String id) throws Exception {
        assertCode(expected, () -> materialize(provider(root, snapshot), id));
        assertRootEmpty();
    }

    private void assertLimitFailure(
            WorkspaceErrorCode expected,
            WorkspaceMaterializationLimits limits,
            ProjectVersionSnapshot snapshot,
            String id) throws Exception {
        assertCode(expected, () -> materialize(provider(root, snapshot), id, limits));
        assertRootEmpty();
    }

    private void assertRootEmpty() throws Exception {
        assertDirectoryEmpty(root);
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    private static boolean probeCaseSensitivity(Path root) throws Exception {
        Path lower = root.resolve("case-probe");
        Path upper = root.resolve("CASE-PROBE");
        Files.writeString(lower, "x");
        try {
            return !Files.exists(upper);
        } finally {
            Files.deleteIfExists(lower);
            Files.deleteIfExists(upper);
        }
    }

    private static void assertCode(WorkspaceErrorCode expected, Runnable operation) {
        WorkspaceException exception = assertThrows(WorkspaceException.class, operation::run);
        assertEquals(expected, exception.code());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
