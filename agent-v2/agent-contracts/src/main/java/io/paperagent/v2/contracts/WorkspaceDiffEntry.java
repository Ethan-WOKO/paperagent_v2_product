package io.paperagent.v2.contracts;

import java.util.Map;
import java.util.Optional;

public record WorkspaceDiffEntry(
        DiffKind kind,
        ProjectPath path,
        Optional<ProjectPath> targetPath,
        Optional<ContentHash> beforeHash,
        Optional<ContentHash> afterHash,
        Map<String, String> metadata) {

    public WorkspaceDiffEntry {
        Contracts.required(kind, "workspaceDiffEntry.kind");
        Contracts.required(path, "workspaceDiffEntry.path");
        targetPath = Contracts.required(targetPath, "workspaceDiffEntry.targetPath");
        beforeHash = Contracts.required(beforeHash, "workspaceDiffEntry.beforeHash");
        afterHash = Contracts.required(afterHash, "workspaceDiffEntry.afterHash");
        metadata = Contracts.map(metadata, "workspaceDiffEntry.metadata");
        metadata.forEach((key, value) -> {
            Contracts.text(key, "workspaceDiffEntry.metadata.key");
            Contracts.text(value, "workspaceDiffEntry.metadata.value");
        });
        boolean valid = switch (kind) {
            case ADD -> targetPath.isEmpty() && beforeHash.isEmpty() && afterHash.isPresent();
            case MODIFY -> targetPath.isEmpty() && beforeHash.isPresent() && afterHash.isPresent()
                    && !beforeHash.get().equals(afterHash.get());
            case DELETE -> targetPath.isEmpty() && beforeHash.isPresent() && afterHash.isEmpty();
            case RENAME -> targetPath.isPresent() && !path.equals(targetPath.get())
                    && beforeHash.isPresent() && afterHash.isPresent();
        };
        if (!valid) {
            Contracts.fail(ViolationCode.INVALID_DIFF_ENTRY, "workspaceDiffEntry",
                    "path/hash combination is invalid for " + kind);
        }
    }
}
