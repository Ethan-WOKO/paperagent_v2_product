package com.yanban.api.agent;

import java.util.List;
import java.util.Objects;

/**
 * The policy that crosses the model/executor boundary. Its allow-list is never null:
 * an empty list explicitly denies every tool.
 */
public record ResolvedToolPolicy(
        List<String> allowedTools,
        int maxToolCalls,
        int maxDuplicateToolCalls,
        String reason
) {
    public ResolvedToolPolicy {
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools must be resolved"));
        if (maxToolCalls < 0 || maxDuplicateToolCalls < 0) {
            throw new IllegalArgumentException("tool call limits must not be negative");
        }
        reason = reason == null || reason.isBlank() ? "resolved" : reason;
    }

    public static ResolvedToolPolicy resolve(List<String> inheritedAllowedTools,
                                             List<String> requestedAllowedTools,
                                             int maxToolCalls,
                                             int maxDuplicateToolCalls,
                                             String reason) {
        List<String> inherited = List.copyOf(Objects.requireNonNull(inheritedAllowedTools,
                "inheritedAllowedTools must be resolved before merging"));
        // null is inheritance only while policies are being merged; [] remains an explicit denial.
        List<String> resolved = requestedAllowedTools == null ? inherited : requestedAllowedTools;
        return new ResolvedToolPolicy(resolved, maxToolCalls, maxDuplicateToolCalls, reason);
    }

    public static ResolvedToolPolicy denyAll(int maxToolCalls, int maxDuplicateToolCalls, String reason) {
        return new ResolvedToolPolicy(List.of(), maxToolCalls, maxDuplicateToolCalls, reason);
    }
}
