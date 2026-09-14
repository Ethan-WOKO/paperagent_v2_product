package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.service.PaperOrchestrator;
import com.yanban.paper.service.PaperTaskService;
import com.yanban.paper.web.PaperProcessRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.yanban.core.tool.ToolExecutionContext;

@Service
public class PaperPolishStartService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PaperPolishInputResolver inputs;
    private final PaperTaskService pipeline;
    private final PaperTaskRepository tasks;
    private final PaperOrchestrator orchestrator;

    public PaperPolishStartService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PaperPolishInputResolver inputs, PaperTaskService pipeline, PaperTaskRepository tasks,
            PaperOrchestrator orchestrator) {
        this.jdbc = jdbc;
        this.inputs = inputs;
        this.pipeline = pipeline;
        this.tasks = tasks;
        this.orchestrator = orchestrator;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public StartResult start(Long userId, Long projectId, JsonNode arguments) {
        String scope = ToolExecutionContext.getInvocationScope();
        if (userId == null || userId <= 0 || scope == null || scope.isBlank() || scope.length() > 2000) {
            throw bad("缺少服务器论文启动请求标识，无法安全重放。");
        }
        var request = inputs.validate(arguments);
        String key = digest(userId + "\n" + projectId + "\n" + scope);
        String requestDigest;
        try { requestDigest = digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(request)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw bad("论文启动参数无法编码。"); }
        // A shared existing owner row serializes absent-row contenders across API processes.
        StartResult result = transaction.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM sys_users WHERE id = ? FOR UPDATE", Long.class, userId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不可访问");
            }
            List<Binding> found = jdbc.query("SELECT request_digest, task_id, source_sha256 FROM paper_tool_starts WHERE user_id = ? AND request_key = ?",
                    (rs, row) -> new Binding(rs.getString(1), rs.getLong(2), rs.getString(3)), userId, key);
            if (!found.isEmpty()) {
                Binding binding = found.get(0);
                if (!binding.requestDigest().equals(requestDigest)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "同一论文启动请求的输入或参数已改变，请在新的对话请求中明确发起新任务。");
                }
                // Authorization is current; persisted source bytes/hash remain frozen. Never reread storage on replay.
                inputs.authorize(userId, projectId, request);
                return from(owned(userId, binding.taskId()), true, binding.sourceSha256());
            }
            var source = inputs.resolve(userId, projectId, request);
            var process = new PaperProcessRequest(source.tex(), source.bib(), null,
                    null, null, null, null, null, false, request.targetLanguage(), "paper-tool." + key);
            var created = pipeline.createTaskForTool(userId, process, "paper-tool." + key);
            jdbc.update("INSERT INTO paper_tool_starts (user_id, request_key, request_digest, task_id, source_sha256) VALUES (?, ?, ?, ?, ?)",
                    userId, key, requestDigest, created.id(), source.sha256());
            return new StartResult(created.id(), created.status(), created.currentStage(), false, source.sha256());
        });
        // If the process dies or submission fails here, READY survives. Replay queues the SAME task.
        // Competing queued workers atomically claim STARTED before entering the existing pipeline.
        if ("PENDING".equals(result.status())) {
            orchestrator.startTaskIfClaimed(result.taskId(), () -> Boolean.TRUE.equals(transaction.execute(status -> {
                PaperTask task = owned(userId, result.taskId());
                if (!"PENDING".equals(task.getStatus())) return false;
                return jdbc.update("UPDATE paper_tool_starts SET dispatch_state = 'STARTED' WHERE user_id = ? AND request_key = ? AND dispatch_state = 'READY'",
                        userId, key) == 1;
            })));
        }
        return result;
    }

    private PaperTask owned(Long userId, Long taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在或不可访问"));
    }

    private static StartResult from(PaperTask task, boolean replayed, String hash) {
        return new StartResult(task.getId(), task.getStatus(), task.getCurrentStage(), replayed, hash);
    }

    static String digest(String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Binding(String requestDigest, Long taskId, String sourceSha256) {}
    public record StartResult(Long taskId, String status, String stage, boolean replayed, String sourceSha256) {
        public boolean terminal() { return Set.of("COMPLETED", "FAILED", "CANCELLED", "STOPPED").contains(status); }
    }
}
