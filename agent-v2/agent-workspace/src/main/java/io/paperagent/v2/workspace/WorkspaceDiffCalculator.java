package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceDiffEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class WorkspaceDiffCalculator {
    private WorkspaceDiffCalculator() {
    }

    static List<WorkspaceDiffEntry> calculate(
            Map<ProjectPath, ContentHash> baseline,
            Map<ProjectPath, ContentHash> current) {
        TreeMap<ProjectPath, ContentHash> deleted = new TreeMap<>(pathComparator());
        TreeMap<ProjectPath, ContentHash> added = new TreeMap<>(pathComparator());
        List<WorkspaceDiffEntry> result = new ArrayList<>();

        baseline.forEach((path, before) -> {
            ContentHash after = current.get(path);
            if (after == null) {
                deleted.put(path, before);
            } else if (!after.equals(before)) {
                result.add(entry(DiffKind.MODIFY, path, null, before, after));
            }
        });
        current.forEach((path, after) -> {
            if (!baseline.containsKey(path)) {
                added.put(path, after);
            }
        });

        Map<ContentHash, List<ProjectPath>> deletedByHash = groupByHash(deleted);
        Map<ContentHash, List<ProjectPath>> addedByHash = groupByHash(added);
        for (Map.Entry<ContentHash, List<ProjectPath>> group : deletedByHash.entrySet()) {
            List<ProjectPath> addedPaths = addedByHash.getOrDefault(group.getKey(), List.of());
            if (group.getValue().size() == 1 && addedPaths.size() == 1) {
                ProjectPath from = group.getValue().get(0);
                ProjectPath to = addedPaths.get(0);
                result.add(entry(DiffKind.RENAME, from, to, group.getKey(), group.getKey()));
                deleted.remove(from);
                added.remove(to);
            }
        }
        deleted.forEach((path, hash) ->
                result.add(entry(DiffKind.DELETE, path, null, hash, null)));
        added.forEach((path, hash) ->
                result.add(entry(DiffKind.ADD, path, null, null, hash)));
        result.sort(Comparator
                .comparing((WorkspaceDiffEntry item) -> item.path().value())
                .thenComparing(item -> item.kind().name())
                .thenComparing(item -> item.targetPath().map(ProjectPath::value).orElse("")));
        return List.copyOf(result);
    }

    static Comparator<ProjectPath> pathComparator() {
        return Comparator.comparing(ProjectPath::value);
    }

    private static Map<ContentHash, List<ProjectPath>> groupByHash(
            Map<ProjectPath, ContentHash> paths) {
        Map<ContentHash, List<ProjectPath>> grouped = new LinkedHashMap<>();
        paths.forEach((path, hash) ->
                grouped.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(path));
        return grouped;
    }

    private static WorkspaceDiffEntry entry(
            DiffKind kind,
            ProjectPath path,
            ProjectPath target,
            ContentHash before,
            ContentHash after) {
        return new WorkspaceDiffEntry(
                kind,
                path,
                Optional.ofNullable(target),
                Optional.ofNullable(before),
                Optional.ofNullable(after),
                Map.of());
    }
}
