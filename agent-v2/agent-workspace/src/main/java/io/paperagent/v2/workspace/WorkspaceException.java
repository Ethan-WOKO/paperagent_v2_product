package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ProjectPath;

import java.util.Optional;

/**
 * Boundary exception that deliberately never exposes a host filesystem path.
 */
public final class WorkspaceException extends RuntimeException {
    private final WorkspaceErrorCode code;
    private final String operation;
    private final Optional<ProjectPath> projectPath;

    public WorkspaceException(WorkspaceErrorCode code, String operation, ProjectPath projectPath) {
        super(message(code, operation, projectPath));
        this.code = require(code, "code");
        this.operation = requireText(operation, "operation");
        this.projectPath = Optional.ofNullable(projectPath);
    }

    public WorkspaceException(WorkspaceErrorCode code, String operation) {
        this(code, operation, null);
    }

    public WorkspaceErrorCode code() {
        return code;
    }

    public String operation() {
        return operation;
    }

    public Optional<ProjectPath> projectPath() {
        return projectPath;
    }

    private static String message(WorkspaceErrorCode code, String operation, ProjectPath path) {
        String safeOperation = operation == null || operation.isBlank() ? "workspace" : operation;
        String safeCode = code == null ? WorkspaceErrorCode.REQUIRED_VALUE_MISSING.name() : code.name();
        return path == null
                ? safeOperation + " failed: " + safeCode
                : safeOperation + " failed for project path '" + path.value() + "': " + safeCode;
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        require(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
