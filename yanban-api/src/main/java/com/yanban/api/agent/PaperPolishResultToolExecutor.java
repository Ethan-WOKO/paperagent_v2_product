package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class PaperPolishResultToolExecutor implements ToolExecutor {

    static final String TOOL_NAME = "paper_polish_result";

    private final PaperTaskToolSupport support;
    private final ToolDefinition definition;

    public PaperPolishResultToolExecutor(PaperTaskToolSupport support, ObjectMapper objectMapper) {
        this.support = support;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("taskId").put("type", "integer").put("description", "论文润色任务 ID");
        parameters.putArray("required").add("taskId");
        this.definition = new ToolDefinition(
                TOOL_NAME,
                "查询当前用户已有论文润色任务的结果摘要、建议数量、文献数量和可下载产物列表",
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
        return support.result(call.id(), definition.name(), taskId(call));
    }

    private Long taskId(ToolCall call) {
        return call.arguments() == null || !call.arguments().has("taskId") ? null : call.arguments().path("taskId").asLong();
    }
}
