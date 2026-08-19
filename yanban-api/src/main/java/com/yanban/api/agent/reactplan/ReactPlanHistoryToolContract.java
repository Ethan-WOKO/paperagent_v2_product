package com.yanban.api.agent.reactplan;

import com.yanban.core.tool.ToolDescriptor;
import java.util.List;
import java.util.Set;

public final class ReactPlanHistoryToolContract {
    public static final String SEARCH_TASKS = "search_conversation_tasks";
    public static final String GET_TASK = "get_conversation_task";
    public static final String GET_TRACE = "get_task_execution_trace";
    public static final String SERVER_TASK_ID = "_serverCurrentTaskId";
    public static final String SERVER_SESSION_ID = "_serverCurrentSessionId";
    public static final String SERVER_PROJECT_ID = "_serverCurrentProjectId";
    public static final Set<String> TOOL_NAMES = Set.of(SEARCH_TASKS, GET_TASK, GET_TRACE);
    public static final Set<String> SERVER_ARGUMENTS = Set.of(
            SERVER_TASK_ID, SERVER_SESSION_ID, SERVER_PROJECT_ID);

    private ReactPlanHistoryToolContract() { }

    public static ToolDescriptor descriptor(String name) {
        if (!TOOL_NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown conversation history tool");
        }
        return new ToolDescriptor(name, "history-v1", "conversation-history",
                List.of(ToolDescriptor.CapabilityProfile.PROJECT),
                List.of("project:read"),
                List.of(ToolDescriptor.ResourceScope.SESSION,
                        ToolDescriptor.ResourceScope.PROJECT),
                ToolDescriptor.SideEffectType.NONE,
                ToolDescriptor.ConfirmationPolicy.NEVER,
                ToolDescriptor.AsyncMode.SYNC,
                ToolDescriptor.IdempotencyPolicy.NONE,
                ToolDescriptor.RepeatPolicy.ALLOW_LIMITED,
                true);
    }
}
