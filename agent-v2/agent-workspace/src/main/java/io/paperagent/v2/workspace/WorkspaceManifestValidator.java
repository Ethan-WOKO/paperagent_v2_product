package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class WorkspaceManifestValidator {
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private WorkspaceManifestValidator() {
    }

    static void validateReference(
            ProjectVersionSnapshot snapshot,
            ProjectVersionRef expected) {
        if (!snapshot.version().equals(expected)) {
            throw failure(WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH, null);
        }
    }

    static void validateLimits(
            List<ProjectFileSnapshot> files,
            WorkspaceMaterializationLimits limits) {
        if (files.size() > limits.maxFiles()) {
            throw failure(WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED, null);
        }
        long aggregate = 0;
        for (ProjectFileSnapshot file : files) {
            int size = file.content().length;
            if (size > limits.maxFileBytes()) {
                throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, file.path());
            }
            try {
                aggregate = Math.addExact(aggregate, size);
            } catch (ArithmeticException exception) {
                throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, file.path());
            }
            if (aggregate > limits.maxAggregateBytes()) {
                throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, file.path());
            }
        }
    }

    static void validateHashes(List<ProjectFileSnapshot> files) {
        for (ProjectFileSnapshot file : files) {
            ContentHash actual = WorkspaceHashes.sha256(file.content());
            if (!actual.equals(file.hash())) {
                throw failure(WorkspaceErrorCode.HASH_MISMATCH, file.path());
            }
        }
    }

    static void validatePaths(
            List<ProjectFileSnapshot> files,
            boolean caseSensitive) {
        Set<String> exact = new TreeSet<>();
        Set<String> filesystem = new TreeSet<>();
        for (ProjectFileSnapshot file : files) {
            String path = file.path().value();
            validatePortablePath(file.path(), "materialize");
            if (!exact.add(path)) {
                throw failure(WorkspaceErrorCode.DUPLICATE_PATH, file.path());
            }
            String normalized = Normalizer.normalize(path, Normalizer.Form.NFC);
            String key = caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
            if (!filesystem.add(key)) {
                throw failure(WorkspaceErrorCode.PATH_COLLISION, file.path());
            }
        }
        rejectPrefixCollision(exact);
        rejectPrefixCollision(filesystem);
    }

    static void validatePortablePath(ProjectPath path, String operation) {
        for (String segment : path.value().split("/")) {
            boolean invalidCharacter = segment.chars()
                    .anyMatch(character -> character < 32 || "<>:\"|?*".indexOf(character) >= 0);
            String basename = segment.contains(".")
                    ? segment.substring(0, segment.indexOf('.'))
                    : segment;
            if (invalidCharacter
                    || segment.endsWith(" ")
                    || segment.endsWith(".")
                    || RESERVED_NAMES.contains(basename.toUpperCase(Locale.ROOT))) {
                throw new WorkspaceException(WorkspaceErrorCode.PATH_COLLISION, operation, path);
            }
        }
    }

    static Map<ProjectPath, ContentHash> baseline(List<ProjectFileSnapshot> files) {
        TreeMap<ProjectPath, ContentHash> result =
                new TreeMap<>(WorkspaceDiffCalculator.pathComparator());
        files.forEach(file -> result.put(file.path(), file.hash()));
        return Map.copyOf(result);
    }

    static List<ProjectFileSnapshot> sorted(List<ProjectFileSnapshot> files) {
        return files.stream()
                .sorted(Comparator.comparing(file -> file.path().value()))
                .toList();
    }

    private static void rejectPrefixCollision(Set<String> paths) {
        String previous = null;
        for (String path : paths) {
            if (previous != null && path.startsWith(previous + "/")) {
                throw failure(WorkspaceErrorCode.PATH_COLLISION, new ProjectPath(path));
            }
            previous = path;
        }
    }

    private static WorkspaceException failure(
            WorkspaceErrorCode code,
            ProjectPath path) {
        return new WorkspaceException(code, "materialize", path);
    }
}
