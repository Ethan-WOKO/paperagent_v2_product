package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.core.tool.ToolExecutionContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReactPlanHistoryToolExecutorTest {
    private static final String CURRENT_TASK = "task." + "1".repeat(64);
    private static final String TARGET_TASK = "task." + "2".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private final ReactPlanConversationHistoryService history =
            mock(ReactPlanConversationHistoryService.class);

    @AfterEach
    void clearContext() {
        ToolExecutionContext.clear();
    }

    @Test
    void definitionsAreDistinctCompactAndDoNotExposeServerAuthority() {
        var tools = List.of(
                new SearchConversationTasksToolExecutor(json, history),
                new GetConversationTaskToolExecutor(json, history),
                new GetTaskExecutionTraceToolExecutor(json, history));

        assertThat(tools).extracting(tool -> tool.definition().name())
                .containsExactly(ReactPlanHistoryToolContract.SEARCH_TASKS,
                        ReactPlanHistoryToolContract.GET_TASK,
                        ReactPlanHistoryToolContract.GET_TRACE);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.definition().parameters().toString())
                    .doesNotContain("_server", "userId", "projectId");
            assertThat(tool.descriptor().resourceScopes()).containsExactly(
                    com.yanban.core.tool.ToolDescriptor.ResourceScope.SESSION,
                    com.yanban.core.tool.ToolDescriptor.ResourceScope.PROJECT);
        });
    }

    @Test
    void executesWithServerInjectedScopeAndReturnsOnlyServiceProjection() {
        SearchConversationTasksToolExecutor executor =
                new SearchConversationTasksToolExecutor(json, history);
        ObjectNode projected = json.createObjectNode();
        projected.put("schemaVersion", "1.0");
        projected.put("resultCount", 0);
        projected.putArray("items");
        when(history.search(any(), any())).thenReturn(projected);
        ToolExecutionContext.setCurrentUserId(11L);
        ToolExecutionContext.setCurrentProjectId(14L);

        var result = executor.execute(new ToolCall(
                "call." + "a".repeat(40), executor.definition().name(),
                serverArguments(json.createObjectNode().put("scope", "current_project"))));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isSameAs(projected);
        assertThat(result.version()).isEqualTo("history-v1");
    }

    @Test
    void failsClosedWhenInjectedProjectDoesNotMatchTrustedContext() {
        GetConversationTaskToolExecutor executor =
                new GetConversationTaskToolExecutor(json, history);
        ToolExecutionContext.setCurrentUserId(11L);
        ToolExecutionContext.setCurrentProjectId(14L);
        ObjectNode arguments = serverArguments(
                json.createObjectNode().put("taskId", TARGET_TASK));
        arguments.put(ReactPlanHistoryToolContract.SERVER_PROJECT_ID, 99L);

        var result = executor.execute(new ToolCall(
                "call." + "b".repeat(40), executor.definition().name(), arguments));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.output()).isNull();
    }

    private ObjectNode serverArguments(ObjectNode arguments) {
        arguments.put(ReactPlanHistoryToolContract.SERVER_TASK_ID, CURRENT_TASK);
        arguments.put(ReactPlanHistoryToolContract.SERVER_SESSION_ID, 13L);
        arguments.put(ReactPlanHistoryToolContract.SERVER_PROJECT_ID, 14L);
        return arguments;
    }
}
