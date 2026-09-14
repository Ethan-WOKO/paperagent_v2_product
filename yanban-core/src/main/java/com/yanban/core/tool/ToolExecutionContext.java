package com.yanban.core.tool;

import java.util.List;
import java.util.Set;

public final class ToolExecutionContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_PROJECT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> INVOCATION_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Set<String>> RESOLVED_ALLOWED_TOOLS = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static void setCurrentUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Long getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    /** Server-owned turn identity used to replay asynchronous task creation safely. */
    public static void setInvocationScope(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 256) {
            throw new IllegalArgumentException("invocation scope must be server-attested");
        }
        INVOCATION_SCOPE.set(scope);
    }

    public static String getInvocationScope() {
        return INVOCATION_SCOPE.get();
    }

    /** Server-attested Project identity for one governed model tool call. */
    public static void setCurrentProjectId(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("project id must be server-attested");
        }
        CURRENT_PROJECT_ID.set(projectId);
    }

    public static Long getCurrentProjectId() {
        return CURRENT_PROJECT_ID.get();
    }

    /** Calling-period policy for model-initiated annotation bridges. Null means no authority. */
    public static void setResolvedAllowedTools(Set<String> allowedTools) {
        if (allowedTools == null) {
            throw new IllegalArgumentException("resolved tool policy must not be null");
        }
        RESOLVED_ALLOWED_TOOLS.set(Set.copyOf(allowedTools));
    }

    public static Set<String> getResolvedAllowedTools() {
        return RESOLVED_ALLOWED_TOOLS.get();
    }

    public static boolean isToolAllowed(String toolName) {
        Set<String> allowed = RESOLVED_ALLOWED_TOOLS.get();
        return allowed != null && allowed.contains(toolName);
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
        CURRENT_PROJECT_ID.remove();
        INVOCATION_SCOPE.remove();
        RESOLVED_ALLOWED_TOOLS.remove();
    }
}
