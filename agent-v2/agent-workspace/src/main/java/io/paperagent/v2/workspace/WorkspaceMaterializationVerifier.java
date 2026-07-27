package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Stateless post-write verification for a pending Workspace tree.
 */
final class WorkspaceMaterializationVerifier {
    private WorkspaceMaterializationVerifier() {
    }

    static void verifyWrittenFile(
            Path target,
            ProjectFileSnapshot expected,
            WorkspaceMaterializationLimits limits) {
        if (linkLike(target)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, expected.path());
        }
        BasicFileAttributes attributes = attributes(target, expected.path());
        if (!attributes.isRegularFile()) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    expected.path());
        }
        if (attributes.size() > limits.maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, expected.path());
        }
        ContentHash actual;
        try {
            actual = WorkspaceHashes.sha256(
                    target,
                    limits.maxFileBytes(),
                    "materialize",
                    expected.path());
        } catch (WorkspaceException exception) {
            if (exception.code() == WorkspaceErrorCode.LINK_ESCAPE
                    || exception.code() == WorkspaceErrorCode.FILE_LIMIT_EXCEEDED) {
                throw exception;
            }
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    expected.path());
        }
        if (attributes.size() != expected.content().length
                || !actual.equals(expected.hash())) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    expected.path());
        }
    }

    static void verifyPending(
            Path dataRoot,
            Path stagingRoot,
            List<ProjectFileSnapshot> expectedFiles,
            WorkspaceMaterializationLimits limits) {
        Path container = dataRoot.getParent();
        if (container == null
                || !container.equals(stagingRoot.getParent())
                || dataRoot.equals(stagingRoot)) {
            throw failure(WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED, null);
        }
        requireDirectory(container);
        requireDirectory(dataRoot);
        requireDirectory(stagingRoot);
        verifyContainerEntries(container, dataRoot, stagingRoot);
        Map<String, ExpectedFile> expected = expectedManifest(expectedFiles);
        Set<String> expectedDirectories = expectedDirectories(expectedFiles);
        verifyActualBudgets(
                dataRoot,
                limits,
                expected.size(),
                expectedDirectories);
        Set<String> seen = new HashSet<>();
        try {
            Files.walkFileTree(dataRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes) {
                    if (linkLike(directory)) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, null);
                    }
                    String relative = dataRoot.relativize(directory)
                            .toString()
                            .replace(directory.getFileSystem().getSeparator(), "/");
                    if (!expectedDirectories.contains(relative)) {
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                null);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()
                            || attributes.isOther()
                            || !attributes.isRegularFile()) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, null);
                    }
                    if (attributes.size() > limits.maxFileBytes()) {
                        throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, null);
                    }
                    String relative = relativeValue(dataRoot, file);
                    ExpectedFile expectedFile = expected.get(relative);
                    if (expectedFile == null || !seen.add(relative)) {
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                null);
                    }
                    if (attributes.size() != expectedFile.size()) {
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                expectedFile.path());
                    }
                    ContentHash hash;
                    try {
                        hash = WorkspaceHashes.sha256(
                                file,
                                limits.maxFileBytes(),
                                "materialize",
                                expectedFile.path());
                    } catch (WorkspaceException exception) {
                        if (exception.code() == WorkspaceErrorCode.LINK_ESCAPE
                                || exception.code()
                                == WorkspaceErrorCode.FILE_LIMIT_EXCEEDED) {
                            throw exception;
                        }
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                expectedFile.path());
                    }
                    if (!hash.equals(expectedFile.hash())) {
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                expectedFile.path());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingRoot)) {
                visitPendingEntries(entries, ignored -> {
                    throw failure(
                            WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                            null);
                });
            }
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    null);
        }
        if (seen.size() != expected.size()) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    null);
        }
    }

    static void verifyActiveStructure(
            Path container,
            Path dataRoot,
            Path stagingRoot,
            String operation) {
        if (container == null
                || dataRoot == null
                || stagingRoot == null
                || !container.equals(dataRoot.getParent())
                || !container.equals(stagingRoot.getParent())
                || dataRoot.equals(stagingRoot)) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        }
        requireActiveDirectory(container, operation);
        requireActiveDirectory(dataRoot, operation);
        requireActiveDirectory(stagingRoot, operation);
        verifyActiveContainerEntries(
                container,
                dataRoot,
                stagingRoot,
                operation);
        verifyActiveStagingEmpty(stagingRoot, operation);
        verifyActiveDataTree(dataRoot, operation);
    }

    private static void verifyActiveContainerEntries(
            Path container,
            Path dataRoot,
            Path stagingRoot,
            String operation) {
        boolean dataSeen = false;
        boolean stagingSeen = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(container)) {
            for (Path entry : entries) {
                if (linkLike(entry)) {
                    throw activeFailure(WorkspaceErrorCode.LINK_ESCAPE, operation);
                }
                if (entry.equals(dataRoot)) {
                    dataSeen = true;
                } else if (entry.equals(stagingRoot)) {
                    stagingSeen = true;
                } else {
                    throw activeFailure(
                            WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                            operation);
                }
            }
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (DirectoryIteratorException exception) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        } catch (IOException exception) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        }
        if (!dataSeen || !stagingSeen) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        }
    }

    private static void verifyActiveStagingEmpty(
            Path stagingRoot,
            String operation) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingRoot)) {
            if (entries.iterator().hasNext()) {
                throw activeFailure(
                        WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                        operation);
            }
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (DirectoryIteratorException exception) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        } catch (IOException exception) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        }
    }

    private static void verifyActiveDataTree(
            Path dataRoot,
            String operation) {
        try {
            Files.walkFileTree(dataRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()
                            || attributes.isOther()
                            || !attributes.isDirectory()
                            || linkLike(directory)) {
                        throw activeFailure(
                                WorkspaceErrorCode.LINK_ESCAPE,
                                operation);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()
                            || attributes.isOther()
                            || !attributes.isRegularFile()) {
                        throw activeFailure(
                                WorkspaceErrorCode.LINK_ESCAPE,
                                operation);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(
                        Path file,
                        IOException exception) {
                    if (linkLike(file)) {
                        throw activeFailure(
                                WorkspaceErrorCode.LINK_ESCAPE,
                                operation);
                    }
                    throw activeFailure(
                            WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                            operation);
                }
            });
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        }
    }

    private static void requireActiveDirectory(Path path, String operation) {
        if (linkLike(path)) {
            throw activeFailure(WorkspaceErrorCode.LINK_ESCAPE, operation);
        }
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw activeFailure(
                        WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                        operation);
            }
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw activeFailure(
                    WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                    operation);
        }
    }

    private static void verifyActualBudgets(
            Path dataRoot,
            WorkspaceMaterializationLimits limits,
            int expectedCount,
            Set<String> expectedDirectories) {
        long[] count = {0};
        long[] aggregate = {0};
        try {
            Files.walkFileTree(dataRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes) {
                    if (linkLike(directory)) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, null);
                    }
                    if (!expectedDirectories.contains(
                            relativeValue(dataRoot, directory))) {
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                null);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()
                            || attributes.isOther()
                            || !attributes.isRegularFile()) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, null);
                    }
                    if (attributes.size() > limits.maxFileBytes()) {
                        throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, null);
                    }
                    try {
                        count[0] = Math.incrementExact(count[0]);
                    } catch (ArithmeticException exception) {
                        throw failure(
                                WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED,
                                null);
                    }
                    if (count[0] > limits.maxFiles()) {
                        throw failure(
                                WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED,
                                null);
                    }
                    try {
                        aggregate[0] = Math.addExact(
                                aggregate[0],
                                attributes.size());
                    } catch (ArithmeticException exception) {
                        throw failure(
                                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                                null);
                    }
                    if (aggregate[0] > limits.maxAggregateBytes()) {
                        throw failure(
                                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                                null);
                    }
                    if (count[0] > expectedCount) {
                        throw failure(
                                WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                                null);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    null);
        }
    }

    private static void verifyContainerEntries(
            Path container,
            Path dataRoot,
            Path stagingRoot) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(container)) {
            visitPendingEntries(entries, entry -> {
                if (linkLike(entry)) {
                    throw failure(WorkspaceErrorCode.LINK_ESCAPE, null);
                }
                if (!entry.equals(dataRoot) && !entry.equals(stagingRoot)) {
                    throw failure(
                            WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                            null);
                }
            });
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    null);
        }
    }

    static void visitPendingEntries(
            DirectoryStream<Path> entries,
            Consumer<Path> visitor) {
        try {
            for (Path entry : entries) {
                visitor.accept(entry);
            }
        } catch (DirectoryIteratorException exception) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    null);
        }
    }

    private static Map<String, ExpectedFile> expectedManifest(
            List<ProjectFileSnapshot> expectedFiles) {
        Map<String, ExpectedFile> expected = new HashMap<>();
        for (ProjectFileSnapshot file : expectedFiles) {
            expected.put(
                    file.path().value(),
                    new ExpectedFile(
                            file.path(),
                            file.content().length,
                            file.hash()));
        }
        return expected;
    }

    private static Set<String> expectedDirectories(
            List<ProjectFileSnapshot> expectedFiles) {
        Set<String> expected = new HashSet<>();
        expected.add("");
        for (ProjectFileSnapshot file : expectedFiles) {
            String[] segments = file.path().value().split("/");
            StringBuilder parent = new StringBuilder();
            for (int index = 0; index < segments.length - 1; index++) {
                if (parent.length() > 0) {
                    parent.append('/');
                }
                parent.append(segments[index]);
                expected.add(parent.toString());
            }
        }
        return expected;
    }

    private static BasicFileAttributes attributes(
            Path path,
            ProjectPath projectPath) {
        try {
            return Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    projectPath);
        }
    }

    private static void requireDirectory(Path path) {
        if (linkLike(path)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, null);
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw failure(
                    WorkspaceErrorCode.MATERIALIZATION_VERIFICATION_FAILED,
                    null);
        }
    }

    private static String relativeValue(Path dataRoot, Path path) {
        return dataRoot.relativize(path)
                .toString()
                .replace(path.getFileSystem().getSeparator(), "/");
    }

    private static boolean linkLike(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return attributes.isOther();
        } catch (IOException exception) {
            return false;
        }
    }

    private static WorkspaceException failure(
            WorkspaceErrorCode code,
            ProjectPath path) {
        return new WorkspaceException(code, "materialize", path);
    }

    private static WorkspaceException activeFailure(
            WorkspaceErrorCode code,
            String operation) {
        return new WorkspaceException(code, operation, null);
    }

    private record ExpectedFile(ProjectPath path, long size, ContentHash hash) {
    }
}
