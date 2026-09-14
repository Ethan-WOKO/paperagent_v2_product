package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PaperPolishStartToolExecutor implements ToolExecutor {
    public static final String TOOL_NAME = "paper_polish_start";
    private final PaperPolishStartService service;
    private final ToolDefinition definition;
    private final ObjectMapper json;

    public PaperPolishStartToolExecutor(PaperPolishStartService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode fields = schema.putObject("properties");
        field(fields, "sourceTaskId", "integer", "Reuse the original .tex and optional .bib from an owned paper task for a NEW polish task. To observe that task use status/result instead.");
        field(fields, "documentId", "integer", "Owned uploaded .tex knowledge document ID, as shown in chat attachments. Not a paper task ID.");
        field(fields, "projectPath", "string", "Exact current Project .tex path; requires expectedSha256 obtained from the Project file manifest/read.");
        field(fields, "expectedSha256", "string", "Exact SHA-256 of projectPath. Do not guess.");
        field(fields, "latexText", "string", "Full LaTeX source explicitly supplied by the user, at most 1 MiB UTF-8. Not PDF/Word extraction or a summary.");
        field(fields, "bibDocumentId", "integer", "Optional owned uploaded .bib document ID with documentId.");
        field(fields, "bibProjectPath", "string", "Optional exact .bib Project path with projectPath; requires bibSha256.");
        field(fields, "bibSha256", "string", "Exact SHA-256 of bibProjectPath.");
        field(fields, "bibText", "string", "Optional BibTeX source with latexText.");
        field(fields, "targetLanguage", "string", "Required output language: zh or en.");
        ((ObjectNode) fields.get("targetLanguage")).putArray("enum").add("zh").add("en");
        schema.putArray("required").add("targetLanguage");
        definition = new ToolDefinition(TOOL_NAME,
                "Start the existing asynchronous paper polishing pipeline only on explicit user request. Choose exactly one sourceTaskId/documentId/projectPath/latexText. Supports LaTeX .tex plus optional .bib only. Returns a task link, not completed polishing. Missing input: upload .tex in chat, select a Project .tex file, or open /paper.", schema);
    }

    private static void field(ObjectNode fields, String name, String type, String description) {
        fields.putObject(name).put("type", type).put("description", description);
    }

    @Override public ToolDefinition definition() { return definition; }
    @Override public ToolDescriptor descriptor() { return PaperPolishToolContract.descriptor(TOOL_NAME); }

    @Override public ToolResult execute(ToolCall call) {
        Long userId = ToolExecutionContext.getCurrentUserId();
        if (userId == null || userId <= 0) return ToolResult.failure(call.id(), TOOL_NAME, "缺少当前用户上下文，无法启动论文任务");
        try {
            var result = service.start(userId, ToolExecutionContext.getCurrentProjectId(), call.arguments());
            ObjectNode output = json.createObjectNode();
            output.put("taskId", result.taskId()).put("taskType", "paper_polish")
                    .put("taskUrl", "/paper?taskId=" + result.taskId())
                    .put("status", result.status()).put("currentStage", result.stage())
                    .put("idempotent", result.replayed()).put("started", true)
                    .put("terminal", result.terminal()).put("completed", "COMPLETED".equals(result.status()))
                    .put("sourceSha256", result.sourceSha256()).put("projectFilesModified", false)
                    .put("message", result.replayed() ? "已找到同一启动请求的论文任务，请查询当前进度和结果。"
                            : "论文润色任务已创建，尚未完成。请查询进度或打开论文任务页面。");
            return ToolResult.success(call.id(), TOOL_NAME, output);
        } catch (ResponseStatusException failure) {
            return ToolResult.failure(call.id(), TOOL_NAME, failure.getReason());
        } catch (RuntimeException failure) {
            return ToolResult.failure(call.id(), TOOL_NAME, "论文任务启动未确认，请使用相同请求重试；不要生成新的启动请求。" );
        }
    }
}
