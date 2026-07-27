package io.paperagent.v2.contracts;

/**
 * Retry-stable limits for materializing one isolated Workspace.
 */
public record WorkspaceMaterializationLimits(
        long maxFileBytes,
        long maxAggregateBytes,
        int maxFiles) {

    public WorkspaceMaterializationLimits {
        requireNonNegative(maxFileBytes, "workspaceMaterializationLimits.maxFileBytes");
        requireNonNegative(maxAggregateBytes, "workspaceMaterializationLimits.maxAggregateBytes");
        requireNonNegative(maxFiles, "workspaceMaterializationLimits.maxFiles");
    }

    @Override
    public String toString() {
        return "WorkspaceMaterializationLimits["
                + "maxFileBytes=<provided>, "
                + "maxAggregateBytes=<provided>, "
                + "maxFiles=<provided>]";
    }

    private static void requireNonNegative(long value, String path) {
        if (value < 0) {
            Contracts.fail(ViolationCode.INVALID_WORKSPACE_LIMIT, path,
                    "workspace materialization limit must not be negative");
        }
    }
}
