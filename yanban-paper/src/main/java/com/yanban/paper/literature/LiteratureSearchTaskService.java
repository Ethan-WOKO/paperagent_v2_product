package com.yanban.paper.literature;

import com.yanban.core.agent.AgentTaskEventCreateRequest;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskEventTypes;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.core.agent.AgentTaskStatus;
import com.yanban.core.agent.AgentTaskUpsertRequest;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiteratureSearchTaskService {

    public static final String STATUS_PENDING = AgentTaskStatus.PENDING.value();
    public static final String STATUS_RUNNING = AgentTaskStatus.RUNNING.value();
    public static final String STATUS_CANCEL_REQUESTED = AgentTaskStatus.CANCEL_REQUESTED.value();
    public static final String STATUS_CANCELLING = AgentTaskStatus.CANCELLING.value();
    public static final String STATUS_COMPLETED = AgentTaskStatus.COMPLETED.value();
    public static final String STATUS_FAILED = AgentTaskStatus.FAILED.value();
    public static final String STATUS_CANCELLED = AgentTaskStatus.CANCELLED.value();

    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED);
    private static final Set<String> CANCEL_STATUSES = Set.of(STATUS_CANCEL_REQUESTED, STATUS_CANCELLING, STATUS_CANCELLED);
    private static final Set<String> ACTIVE_STATUSES = Set.of(STATUS_PENDING, STATUS_RUNNING);
    private static final Duration DEFAULT_PENDING_AGE = Duration.ofMinutes(2);
    private static final Duration DEFAULT_RUNNING_TIMEOUT = Duration.ofMinutes(10);
    private static final int DEFAULT_SCAN_BATCH_SIZE = 50;
    private static final int MAX_SCAN_BATCH_SIZE = 200;
    private static final int MAX_EVENT_MESSAGE_LENGTH = 500;

    private final LiteratureSearchTaskRepository tasks;
    private final LiteratureSearchTaskPublisher publisher;
    private final AgentTaskEventRecorder eventRecorder;
    private final AgentTaskRegistry agentTaskRegistry;

    public LiteratureSearchTaskService(LiteratureSearchTaskRepository tasks,
                                       ObjectProvider<LiteratureSearchTaskPublisher> publisherProvider,
                                       ObjectProvider<AgentTaskEventRecorder> eventRecorderProvider) {
        this(tasks, publisherProvider, eventRecorderProvider, null);
    }

    @Autowired
    public LiteratureSearchTaskService(LiteratureSearchTaskRepository tasks,
                                       ObjectProvider<LiteratureSearchTaskPublisher> publisherProvider,
                                       ObjectProvider<AgentTaskEventRecorder> eventRecorderProvider,
                                       ObjectProvider<AgentTaskRegistry> agentTaskRegistryProvider) {
        this.tasks = tasks;
        this.publisher = publisherProvider == null ? null : publisherProvider.getIfAvailable();
        this.eventRecorder = eventRecorderProvider == null ? null : eventRecorderProvider.getIfAvailable();
        this.agentTaskRegistry = agentTaskRegistryProvider == null ? null : agentTaskRegistryProvider.getIfAvailable();
    }

    @Transactional
    public TaskStartResult createTask(Long userId, LiteratureSearchTaskRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户上下文");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request 不能为空");
        }
        String query = normalizeQuery(request.query());
        if (!StringUtils.hasText(query)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        int topK = clampTopK(request.topK());
        Integer yearFrom = request.yearFrom();
        boolean includeBibtex = request.includeBibtex() == null || request.includeBibtex();
        String clientRequestId = clientRequestId(request.clientRequestId());
        String idempotencyKey = idempotencyKey(userId, query, topK, yearFrom, includeBibtex, clientRequestId);
        return tasks.findByIdempotencyKey(idempotencyKey)
                .map(task -> new TaskStartResult(task, true))
                .orElseGet(() -> {
                    LiteratureSearchTask saved = tasks.save(new LiteratureSearchTask(
                            userId,
                            request.projectId(),
                            query,
                            query.toLowerCase(Locale.ROOT),
                            topK,
                            yearFrom,
                            includeBibtex,
                            STATUS_PENDING,
                            "QUEUED",
                            clientRequestId,
                            idempotencyKey
                    ));
                    syncUnifiedTask(saved);
                    recordEvent(saved, AgentTaskEventTypes.TASK_CREATED, "文献检索任务已创建", null);
                    publishAfterCommit(saved);
                    return new TaskStartResult(saved, false);
                });
    }

    @Transactional(readOnly = true)
    public LiteratureSearchTask getTask(Long userId, Long taskId) {
        return ownedTask(userId, taskId);
    }

    @Transactional
    public LiteratureSearchTask requestCancel(Long userId, Long taskId, String cancelReason) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            return task;
        }
        int updated = tasks.requestCancelIfActive(
                taskId, userId, ACTIVE_STATUSES,
                STATUS_CANCEL_REQUESTED, "CANCEL_REQUESTED",
                trimToNull(cancelReason), Instant.now());
        LiteratureSearchTask saved = ownedTask(userId, taskId);
        if (updated == 0) {
            return saved;
        }
        syncUnifiedTask(saved);
        recordEvent(saved, AgentTaskEventTypes.TASK_CANCEL_REQUESTED, "用户请求停止文献检索任务", null);
        return saved;
    }

    @Transactional
    public DispatchRetryResult retryPendingDispatch(Long userId, Long taskId) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (publisher == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文献检索任务分发器不可用");
        }
        if (!STATUS_PENDING.equals(task.getStatus())) {
            return new DispatchRetryResult(task, false, true, messageForRetry(task.getStatus()));
        }
        task.setCurrentStage("RETRY_QUEUED");
        LiteratureSearchTask saved = tasks.save(task);
        syncUnifiedTask(saved);
        recordEvent(saved, AgentTaskEventTypes.TASK_MANUAL_REQUEUED, "用户手动重投文献检索唤醒消息", null);
        publishAfterCommit(saved);
        return new DispatchRetryResult(saved, true, false, "manual dispatch retry queued");
    }

    @Transactional
    public LiteratureSearchTask saveResult(Long userId,
                                           Long taskId,
                                           String resultJson,
                                           Integer rawCandidateCount,
                                           Integer uniqueCandidateCount,
                                           Integer sourceAttempts,
                                           String sourceFailuresJson) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (CANCEL_STATUSES.contains(task.getStatus())) {
            return markCancelled(userId, taskId);
        }
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            return task;
        }
        Instant finishedAt = Instant.now();
        int updated = tasks.completeIfActive(
                taskId, userId, ACTIVE_STATUSES,
                resultJson, rawCandidateCount, uniqueCandidateCount,
                sourceAttempts, sourceFailuresJson,
                STATUS_COMPLETED, "COMPLETE", finishedAt);
        LiteratureSearchTask saved = ownedTask(userId, taskId);
        if (updated == 0) {
            if (CANCEL_STATUSES.contains(saved.getStatus())) {
                return markCancelled(userId, taskId);
            }
            return saved;
        }
        syncUnifiedTask(saved);
        recordEvent(saved, AgentTaskEventTypes.TASK_COMPLETED, "文献检索任务已完成", null);
        return saved;
    }

    @Transactional
    public ScanResult scanStalledTasks(Duration pendingAge, Duration runningTimeout, int batchSize) {
        Instant now = Instant.now();
        int limit = normalizeBatchSize(batchSize);
        Instant pendingBefore = now.minus(normalizeDuration(pendingAge, DEFAULT_PENDING_AGE));
        Instant runningBefore = now.minus(normalizeDuration(runningTimeout, DEFAULT_RUNNING_TIMEOUT));

        int requeued = 0;
        for (LiteratureSearchTask task : tasks.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                STATUS_PENDING,
                pendingBefore,
                PageRequest.of(0, limit))) {
            task.setCurrentStage("REQUEUED");
            LiteratureSearchTask saved = tasks.save(task);
            syncUnifiedTask(saved);
            recordEvent(saved, AgentTaskEventTypes.TASK_REQUEUED, "文献检索任务超时未领取，已重新投递唤醒消息", null);
            publishAfterCommit(saved);
            requeued++;
        }

        int timedOut = 0;
        for (LiteratureSearchTask task : tasks.findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
                STATUS_RUNNING,
                runningBefore,
                PageRequest.of(0, limit))) {
            task.setStatus(STATUS_FAILED);
            task.setCurrentStage("TIMEOUT");
            task.setErrorMessage("文献检索任务运行超时，已停止等待并标记失败");
            task.setFinishedAt(now);
            LiteratureSearchTask saved = tasks.save(task);
            syncUnifiedTask(saved);
            recordEvent(saved, AgentTaskEventTypes.TASK_TIMED_OUT, "文献检索任务运行超时，已标记失败", null);
            timedOut++;
        }

        return new ScanResult(requeued, timedOut);
    }

    @Transactional
    public Optional<LiteratureSearchTask> claimForRun(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        Optional<LiteratureSearchTask> taskOpt = tasks.findById(taskId);
        if (taskOpt.isEmpty()) {
            return Optional.empty();
        }
        LiteratureSearchTask task = taskOpt.get();
        if (STATUS_PENDING.equals(task.getStatus())) {
            task.setStatus(STATUS_RUNNING);
            task.setCurrentStage("SEARCHING");
            task.setStartedAt(Instant.now());
            LiteratureSearchTask saved = tasks.save(task);
            syncUnifiedTask(saved);
            recordEvent(saved, AgentTaskEventTypes.TASK_RUNNING, "文献检索任务开始执行", null);
            return Optional.of(saved);
        }
        if (STATUS_CANCEL_REQUESTED.equals(task.getStatus()) || STATUS_CANCELLING.equals(task.getStatus())) {
            LiteratureSearchTask cancelling = markCancelling(task.getUserId(), task.getId());
            LiteratureSearchTask saved = completeCancelled(cancelling);
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean isCancellationRequested(Long userId, Long taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .map(task -> CANCEL_STATUSES.contains(task.getStatus()))
                .orElse(false);
    }

    @Transactional
    public LiteratureSearchTask markCancelling(Long userId, Long taskId) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (TERMINAL_STATUSES.contains(task.getStatus()) || STATUS_CANCELLED.equals(task.getStatus())) {
            return task;
        }
        if (!STATUS_CANCELLING.equals(task.getStatus())) {
            task.setStatus(STATUS_CANCELLING);
            task.setCurrentStage(StringUtils.hasText(task.getCurrentStage()) ? task.getCurrentStage() : "CANCELLING");
            LiteratureSearchTask saved = tasks.save(task);
            syncUnifiedTask(saved);
            recordEvent(saved, AgentTaskEventTypes.TASK_CANCELLING, "文献检索任务正在安全停止", null);
            return saved;
        }
        return task;
    }

    @Transactional
    public LiteratureSearchTask markCancelled(Long userId, Long taskId) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (STATUS_CANCELLED.equals(task.getStatus())) {
            return task;
        }
        if (!TERMINAL_STATUSES.contains(task.getStatus())) {
            task = markCancelling(userId, taskId);
        }
        return completeCancelled(task);
    }

    @Transactional
    public LiteratureSearchTask markFailed(Long userId, Long taskId, String errorMessage) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (CANCEL_STATUSES.contains(task.getStatus())) {
            return markCancelled(userId, taskId);
        }
        task.setStatus(STATUS_FAILED);
        task.setCurrentStage("FAILED");
        task.setErrorMessage(trimToLength(errorMessage, 1000));
        task.setFinishedAt(Instant.now());
        LiteratureSearchTask saved = tasks.save(task);
        syncUnifiedTask(saved);
        recordEvent(saved, AgentTaskEventTypes.TASK_FAILED, trimToLength(errorMessage, MAX_EVENT_MESSAGE_LENGTH), null);
        return saved;
    }

    public boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    private LiteratureSearchTask ownedTask(Long userId, Long taskId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户上下文");
        }
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 不能为空");
        }
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文献检索任务不存在或不可访问"));
    }

    private int clampTopK(Integer topK) {
        int value = topK == null ? 8 : topK;
        return Math.min(Math.max(value, 1), 20);
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.replaceAll("\\s+", " ").trim();
    }

    private String clientRequestId(String value) {
        return StringUtils.hasText(value) ? value.trim() : UUID.randomUUID().toString();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String idempotencyKey(Long userId, String query, int topK, Integer yearFrom, boolean includeBibtex, String clientRequestId) {
        String raw = userId + "|LITERATURE_SEARCH|" + query.toLowerCase(Locale.ROOT) + "|" + topK + "|"
                + (yearFrom == null ? "" : yearFrom) + "|" + includeBibtex + "|" + clientRequestId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build literature task idempotency key", ex);
        }
    }

    private void publishAfterCommit(LiteratureSearchTask task) {
        if (publisher == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishTaskCreated(task);
                }
            });
            return;
        }
        publisher.publishTaskCreated(task);
    }

    private void recordEvent(LiteratureSearchTask task, String eventType, String message, String payloadJson) {
        if (eventRecorder == null || task == null) {
            return;
        }
        eventRecorder.recordSafely(new AgentTaskEventCreateRequest(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                task.getId(),
                task.getUserId(),
                eventType,
                task.getCurrentStage(),
                task.getStatus(),
                trimToLength(message, MAX_EVENT_MESSAGE_LENGTH),
                payloadJson
        ));
    }

    private LiteratureSearchTask completeCancelled(LiteratureSearchTask task) {
        task.setStatus(STATUS_CANCELLED);
        task.setCurrentStage("CANCELLED");
        task.setFinishedAt(Instant.now());
        LiteratureSearchTask saved = tasks.save(task);
        syncUnifiedTask(saved);
        recordEvent(saved, AgentTaskEventTypes.TASK_CANCELLED, "文献检索任务已取消", null);
        return saved;
    }

    private void syncUnifiedTask(LiteratureSearchTask task) {
        if (agentTaskRegistry == null || task == null) {
            return;
        }
        agentTaskRegistry.upsertSafely(new AgentTaskUpsertRequest(
                task.getUserId(),
                task.getProjectId(),
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                task.getId(),
                task.getStatus(),
                "LONG_RUNNING_TOOL_TASK",
                task.getClientRequestId(),
                task.getQuery(),
                task.getQuery(),
                STATUS_COMPLETED.equals(task.getStatus()) ? 100 : null,
                task.getCurrentStage(),
                null,
                task.getErrorMessage(),
                task.getCancelReason(),
                0,
                0,
                task.getStartedAt(),
                task.getFinishedAt()
        ));
    }

    private String trimToLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private Duration normalizeDuration(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return DEFAULT_SCAN_BATCH_SIZE;
        }
        return Math.min(batchSize, MAX_SCAN_BATCH_SIZE);
    }

    public record TaskStartResult(LiteratureSearchTask task, boolean idempotent) {
    }

    public record DispatchRetryResult(LiteratureSearchTask task, boolean retryAccepted, boolean idempotent, String message) {
    }

    public record ScanResult(int requeuedPendingCount, int timedOutRunningCount) {
        public boolean hasWork() {
            return requeuedPendingCount > 0 || timedOutRunningCount > 0;
        }
    }

    private String messageForRetry(String status) {
        if (STATUS_RUNNING.equals(status)) {
            return "task already running";
        }
        if (TERMINAL_STATUSES.contains(status)) {
            return "task already in terminal state";
        }
        if (STATUS_CANCEL_REQUESTED.equals(status) || STATUS_CANCELLING.equals(status)) {
            return "task is cancelling";
        }
        return "task is not pending";
    }
}
