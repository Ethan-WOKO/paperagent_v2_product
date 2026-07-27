package io.paperagent.v2.workspace;

final class WorkspaceValues {
    private WorkspaceValues() {
    }

    static <T> T require(T value, String operation) {
        if (value == null) {
            throw new WorkspaceException(WorkspaceErrorCode.REQUIRED_VALUE_MISSING, operation);
        }
        return value;
    }
}
