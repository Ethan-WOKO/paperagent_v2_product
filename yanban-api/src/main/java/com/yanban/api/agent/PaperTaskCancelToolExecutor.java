package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class PaperTaskCancelToolExecutor implements ToolExecutor {

    static final String TOOL_NAME = "paper_task_cancel";

    private final PaperTaskToolSupport support;
    private final ToolDefinition definition;

    public PaperTaskCancelToolExecutor(PaperTaskToolSupport support, ObjectMapper objectMapper) {
        this.support = support;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("taskId").put("type", "integer").put("description", "要取消的论文润色任务 ID");
        properties.putObject("cancelReason").put("type", "string").put("description", "可选取消原因，仅用于模型解释，不改变后端取消状态机");
        parameters.putArray("required").add("taskId");
        this.definition = new ToolDefinition(
                TOOL_NAME,
                "请求取消当前用户的论文润色任务；只受理取消请求，不强杀线程，最终状态由任务检查点写入",
                parameters
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public com.yanban.core.tool.ToolDescriptor descriptor() {
        return PaperPolishToolContract.descriptor(TOOL_NAME);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        return support.cancel(call.id(), definition.name(), taskId(call), cancelReason(call));
    }

    private Long taskId(ToolCall call) {
        return call.arguments() == null || !call.arguments().has("taskId") ? null : call.arguments().path("taskId").asLong();
    }

    private String cancelReason(ToolCall call) {
        return call.arguments() == null ? null : call.arguments().path("cancelReason").asText(null);
    }
}
