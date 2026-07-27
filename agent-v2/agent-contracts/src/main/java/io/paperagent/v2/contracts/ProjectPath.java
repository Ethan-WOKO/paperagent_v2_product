package io.paperagent.v2.contracts;

import java.util.Arrays;

/**
 * Normalized, project-relative POSIX path.
 */
public record ProjectPath(String value) {
    public ProjectPath {
        Contracts.text(value, "projectPath");
        if (value.startsWith("/")
                || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*")
                || value.contains("\\")
                || value.endsWith("/")
                || Arrays.stream(value.split("/", -1))
                .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
            Contracts.fail(ViolationCode.INVALID_PATH, "projectPath",
                    "path must be a normalized project-relative POSIX path");
        }
    }
}
