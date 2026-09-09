package com.yanban.api.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.service.PaperOrchestrator;
import com.yanban.paper.service.PaperTaskService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class PaperTaskToolSupport {

    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "STOPPED");
    private static final Set<String> DOWNLOADABLE_ARTIFACT_TYPES = Set.of(
            "polished_tex",
            "suggested_bib",
            "suggested_bib_novel",
            "review_report",
            "retrieved_literature_json",
            "retrieved_literature_md",
            "source_bib"
    );

    private final PaperTaskRepository tasks;
    private final PaperTaskArtifactRepository artifacts;
    private final SuggestionRepository suggestions;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final PaperTaskService paperTaskService;
    private final PaperOrchestrator paperOrchestrator;
    private final ObjectMapper objectMapper;

    PaperTaskToolSupport(PaperTaskRepository tasks,
                         PaperTaskArtifactRepository artifacts,
                         SuggestionRepository suggestions,
                         PaperTaskLiteratureRepository taskLiterature,
                         PaperTaskService paperTaskService,
                         PaperOrchestrator paperOrchestrator,
                         ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.suggestions = suggestions;
        this.taskLiterature = taskLiterature;
        this.paperTaskService = paperTaskService;
        this.paperOrchestrator = paperOrchestrator;
        this.objectMapper = objectMapper;
    }

    ToolResult status(String toolCallId, String toolName, Long taskId) {
        return withUser(toolCallId, toolName, userId -> ToolResult.success(toolCallId, toolName, taskSummary(userId, taskId)));
    }

    ToolResult result(String toolCallId, String toolName, Long taskId) {
        return withUser(toolCallId, toolName, userId -> {
            PaperTask task = ownedTask(userId, taskId);
            ObjectNode output = taskSummary(userId, task);
            boolean downloadAvailable = paperTaskService.hasDownloadableResult(userId, taskId);
            output.put("downloadAvailable", downloadAvailable);
            if (downloadAvailable) {
                output.put("downloadUrl", "/api/v1/paper/tasks/" + taskId + "/download");
                output.put("downloadFilename", paperTaskService.downloadFilename(userId, taskId));
                output.put("downloadContentType", paperTaskService.downloadContentType(userId, taskId));
            }
            output.put("suggestionCount", suggestions.findByTaskIdOrderByCreatedAt(taskId).size());
            output.put("selectedLiteratureCount", taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).size());
            output.set("artifacts", artifactSummaries(taskId));
            return ToolResult.success(toolCallId, toolName, output);
        });
    }

    ToolResult cancel(String toolCallId, String toolName, Long taskId) {
        return cancel(toolCallId, toolName, taskId, null);
    }

    ToolResult cancel(String toolCallId, String toolName, Long taskId, String cancelReason) {
        return withUser(toolCallId, toolName, userId -> {
            PaperTask before = ownedTask(userId, taskId);
            boolean terminalBefore = isTerminal(before.getStatus());
            boolean alreadyCancelling = "CANCEL_REQUESTED".equals(before.getStatus()) || "CANCELLING".equals(before.getStatus());
            if (!alreadyCancelling) paperOrchestrator.stop(userId, taskId, cancelReason);
            PaperTask after = ownedTask(userId, taskId);
            ObjectNode output = taskSummary(userId, after);
            output.put("cancelAccepted", !terminalBefore);
            output.put("idempotent", terminalBefore || alreadyCancelling);
            output.put("cancelled", "CANCELLED".equals(after.getStatus()));
            output.put("message", terminalBefore ? "任务已是终态，取消请求保持幂等" : "取消请求已受理");
            return ToolResult.success(toolCallId, toolName, output);
        });
    }

    private ObjectNode taskSummary(Long userId, Long taskId) {
        return taskSummary(userId, ownedTask(userId, taskId));
    }

    private ObjectNode taskSummary(Long userId, PaperTask task) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("taskId", task.getId());
        output.put("taskType", "paper_polish");
        output.put("taskUrl", "/paper?taskId=" + task.getId());
        output.put("projectFilesModified", false);
        output.put("completed", "COMPLETED".equals(task.getStatus()));
        boolean waiting = "WAITING_INPUT".equals(task.getStatus()) || "PAUSED".equals(task.getStatus());
        boolean cancelling = "CANCEL_REQUESTED".equals(task.getStatus()) || "CANCELLING".equals(task.getStatus());
        output.put("requiresUserInput", waiting);
        output.put("cancelling", cancelling);
        output.put("pollRecommended", !waiting && !isTerminal(task.getStatus()));
        output.put("nextAction", waiting ? "请打开论文任务页面，完成结构确认或继续任务。"
                : cancelling ? "取消已受理，等待工作线程到达安全检查点。"
                : isTerminal(task.getStatus()) ? "查看论文任务结果和产物。" : "任务尚未完成，请稍后查询进度。");
        output.put("title", task.getTitle());
        output.put("sourceFilename", task.getSourceFilename());
        output.put("status", task.getStatus());
        output.put("currentStage", task.getCurrentStage());
        if (task.getErrorMessage() != null) {
            output.put("errorMessage", task.getErrorMessage());
        }
        output.put("targetLanguage", task.getTargetLanguage());
        if (task.getMode() != null) {
            output.put("mode", task.getMode());
        }
        if (task.getLiteratureMinCount() != null) {
            output.put("literatureMinCount", task.getLiteratureMinCount());
        }
        if (task.getLiteratureCount() != null) {
            output.put("literatureCount", task.getLiteratureCount());
        }
        output.put("terminal", isTerminal(task.getStatus()));
        output.put("cancellable", !isTerminal(task.getStatus()) && !cancelling);
        output.put("downloadAvailable", safeDownloadAvailable(userId, task.getId()));
        output.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        output.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        output.set("artifacts", artifactSummaries(task.getId()));
        return output;
    }

    private PaperTask ownedTask(Long userId, Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId is required");
        }
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在或不可访问"));
    }

    private ArrayNode artifactSummaries(Long taskId) {
        ArrayNode array = objectMapper.createArrayNode();
        List<PaperTaskArtifact> items = artifacts.findByTaskIdOrderByCreatedAt(taskId);
        for (PaperTaskArtifact artifact : items) {
            ObjectNode node = array.addObject();
            node.put("artifactId", artifact.getId());
            node.put("type", artifact.getType());
            node.put("version", artifact.getVersion());
            node.put("artifactStatus", artifact.getArtifactStatus());
            node.put("downloadable", DOWNLOADABLE_ARTIFACT_TYPES.contains(artifact.getType())
                    && PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()));
            String filename = metadataFilename(artifact.getMetadataJson());
            if (filename != null) {
                node.put("filename", filename);
            }
            if (artifact.getCreatedAt() != null) {
                node.put("createdAt", artifact.getCreatedAt().toString());
            }
        }
        return array;
    }

    private boolean safeDownloadAvailable(Long userId, Long taskId) {
        try {
            return paperTaskService.hasDownloadableResult(userId, taskId);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private ToolResult withUser(String toolCallId, String toolName, ToolOperation operation) {
        Long userId = ToolExecutionContext.getCurrentUserId();
        if (userId == null || userId <= 0) {
            return ToolResult.failure(toolCallId, toolName, "缺少当前用户上下文，无法访问论文任务");
        }
        try {
            return operation.execute(userId);
        } catch (ResponseStatusException ex) {
            return ToolResult.failure(toolCallId, toolName, ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason());
        } catch (RuntimeException ex) {
            return ToolResult.failure(toolCallId, toolName, "论文任务暂不可读取，请稍后重试。");
        }
    }

    private boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    private String metadataFilename(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        String filename;
        try {
            filename = objectMapper.readTree(metadata).path("filename").asText(null);
        } catch (JsonProcessingException ex) {
            return null;
        }
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            return null;
        }
        return filename.trim();
    }

    @FunctionalInterface
    private interface ToolOperation {
        ToolResult execute(Long userId);
    }
}
