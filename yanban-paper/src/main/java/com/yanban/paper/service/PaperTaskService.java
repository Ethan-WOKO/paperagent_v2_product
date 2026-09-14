package com.yanban.paper.service;

import com.yanban.core.agent.AgentTaskEventCreateRequest;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskEventTypes;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.core.agent.AgentTaskUpsertRequest;
import com.yanban.core.user.UserAccountPolicy;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.web.PaperProcessRequest;
import com.yanban.paper.web.PaperTaskHistoryResponse;
import com.yanban.paper.web.PaperTaskResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperTaskService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String INPUT_FORMAT_LATEX = "LATEX";
    private static final String EVENT_MESSAGE_TASK_CREATED = "paper polish task created";

    private final PaperTaskRepository paperTaskRepository;
    private final PaperTaskArtifactRepository artifactRepository;
    private final PaperStorageService paperStorageService;
    private final PaperOrchestrator paperOrchestrator;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;
    private final AgentTaskEventRecorder taskEvents;
    private final AgentTaskRegistry agentTaskRegistry;

    public PaperTaskService(PaperTaskRepository paperTaskRepository,
                            PaperTaskArtifactRepository artifactRepository,
                            PaperStorageService paperStorageService,
                            PaperOrchestrator paperOrchestrator,
                            ObjectProvider<UserAccountPolicy> accountPolicy,
                            AgentTaskEventRecorder taskEvents,
                            AgentTaskRegistry agentTaskRegistry) {
        this.paperTaskRepository = paperTaskRepository;
        this.artifactRepository = artifactRepository;
        this.paperStorageService = paperStorageService;
        this.paperOrchestrator = paperOrchestrator;
        this.accountPolicy = accountPolicy;
        this.taskEvents = taskEvents;
        this.agentTaskRegistry = agentTaskRegistry;
    }

    @Transactional
    public PaperTaskResponse createTask(Long userId, PaperProcessRequest request, String clientRequestIdHeader) {
        return createTask(userId, request, clientRequestIdHeader, true);
    }

    /** Product tool adapter binds the committed task before dispatching through the same orchestrator. */
    @Transactional
    public PaperTaskResponse createTaskForTool(Long userId, PaperProcessRequest request, String clientRequestId) {
        return createTask(userId, request, clientRequestId, false);
    }

    private PaperTaskResponse createTask(Long userId, PaperProcessRequest request,
                                         String clientRequestIdHeader, boolean dispatch) {
        MultipartFile file = request.mainTex() == null ? request.file() : request.mainTex();
        MultipartFile bibFile = request.bibFile();
        validateTexFile(file);
        validateBibFile(bibFile);

        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null) {
            long totalBytes = file.getSize() + (bibFile == null ? 0 : bibFile.getSize());
            policy.assertCanCreatePaperTask(userId, totalBytes);
        }

        String clientRequestId = resolveClientRequestId(clientRequestIdHeader, request.clientRequestId());
        int maxLiteratureCount = normalizeLiteratureCount(request.literatureCount());
        int minLiteratureCount = normalizeLiteratureMinCount(request.literatureMinCount(), maxLiteratureCount);
        int scoreThreshold = normalizeScoreThreshold(request.scoreThreshold());
        int maxRounds = normalizeMaxRounds(request.maxRounds());
        int innerMaxAttempts = normalizeInnerMaxAttempts(request.innerMaxAttempts());
        String idempotencyKey = buildIdempotencyKey(userId, request, file, bibFile, clientRequestId,
                maxLiteratureCount, minLiteratureCount, scoreThreshold, maxRounds, innerMaxAttempts);

        PaperTask existing = paperTaskRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return PaperTaskResponse.from(existing, existing.getLiteratureCount(), true);
        }

        String objectKey = paperStorageService.storeOriginal(userId, file);
        String bibObjectKey = bibFile == null || bibFile.isEmpty() ? null : paperStorageService.storeOriginal(userId, bibFile);
        String title = resolveTitle(file);
        PaperTask task = new PaperTask(
                userId,
                title,
                file.getOriginalFilename(),
                objectKey,
                STATUS_PENDING,
                request.targetLanguage(),
                "UPLOAD_RECEIVED",
                null,
                clientRequestId,
                idempotencyKey
        );
        task.setInputFormat(INPUT_FORMAT_LATEX);
        task.setMainEntry(file.getOriginalFilename());
        String baseMode = bibObjectKey == null ? "LATEX_ONLY" : "LATEX_BIB";
        task.setMode(baseMode);
        task.setLiteratureCount(maxLiteratureCount);
        task.setLiteratureMinCount(minLiteratureCount);
        task.setScoreThreshold(scoreThreshold);
        task.setMaxRounds(maxRounds);
        task.setInnerMaxAttempts(innerMaxAttempts);

        PaperTask saved = paperTaskRepository.save(task);
        saveSourceArtifact(saved.getId(), "source_tex", objectKey, file.getOriginalFilename());
        if (bibObjectKey != null) {
            saveSourceArtifact(saved.getId(), "source_bib", bibObjectKey, bibFile.getOriginalFilename());
        }
        recordTaskCreatedAfterCommit(saved);
        if (dispatch) startTaskAfterCommit(saved.getId());
        return PaperTaskResponse.from(saved, saved.getLiteratureCount(), false);
    }

    private void recordTaskCreatedAfterCommit(PaperTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordTaskCreated(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                recordTaskCreated(task);
            }
        });
    }

    private void recordTaskCreated(PaperTask task) {
        agentTaskRegistry.upsertSafely(toUnifiedTask(task));
        taskEvents.recordSafely(new AgentTaskEventCreateRequest(
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                task.getId(),
                task.getUserId(),
                AgentTaskEventTypes.TASK_CREATED,
                task.getCurrentStage(),
                task.getStatus(),
                EVENT_MESSAGE_TASK_CREATED,
                null
        ));
    }

    private AgentTaskUpsertRequest toUnifiedTask(PaperTask task) {
        return new AgentTaskUpsertRequest(
                task.getUserId(),
                null,
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                AgentTaskRegistry.SOURCE_PAPER_TASK,
                task.getId(),
                task.getStatus(),
                "LONG_RUNNING_TOOL_TASK",
                task.getClientRequestId(),
                task.getTitle(),
                task.getSourceFilename(),
                null,
                task.getCurrentStage(),
                null,
                task.getErrorMessage(),
                null,
                0,
                0,
                null,
                null
        );
    }

    private Integer normalizeLiteratureCount(Integer literatureCount) {
        if (literatureCount == null) {
            return 20;
        }
        return Math.max(1, Math.min(100, literatureCount));
    }

    private Integer normalizeLiteratureMinCount(Integer literatureMinCount, int maxLiteratureCount) {
        int value = literatureMinCount == null ? Math.min(8, maxLiteratureCount) : literatureMinCount;
        return Math.max(1, Math.min(maxLiteratureCount, value));
    }

    private int normalizeScoreThreshold(Integer scoreThreshold) {
        if (scoreThreshold == null) {
            return 80;
        }
        return Math.max(0, Math.min(100, scoreThreshold));
    }

    private int normalizeMaxRounds(Integer maxRounds) {
        if (maxRounds == null) {
            return 3;
        }
        return Math.max(1, Math.min(20, maxRounds));
    }

    private int normalizeInnerMaxAttempts(Integer innerMaxAttempts) {
        if (innerMaxAttempts == null) {
            return 2;
        }
        return Math.max(1, Math.min(20, innerMaxAttempts));
    }

    private void startTaskAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            paperOrchestrator.startTask(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paperOrchestrator.startTask(taskId);
            }
        });
    }

    private void validateTexFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "please upload a LaTeX .tex file");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".tex")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "main document only supports .tex");
        }
    }

    private void validateBibFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".bib")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bibliography only supports .bib");
        }
    }

    private String resolveClientRequestId(String headerValue, String bodyValue) {
        if (StringUtils.hasText(headerValue)) {
            return headerValue.trim();
        }
        if (StringUtils.hasText(bodyValue)) {
            return bodyValue.trim();
        }
        return UUID.randomUUID().toString();
    }

    private String buildIdempotencyKey(Long userId,
                                       PaperProcessRequest request,
                                       MultipartFile file,
                                       MultipartFile bibFile,
                                       String clientRequestId,
                                       int maxLiteratureCount,
                                       int minLiteratureCount,
                                       int scoreThreshold,
                                       int maxRounds,
                                       int innerMaxAttempts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, String.valueOf(userId));
            updateDigest(digest, "PAPER_POLISH");
            updateDigest(digest, clientRequestId);
            updateDigest(digest, request.targetLanguage());
            updateDigest(digest, String.valueOf(maxLiteratureCount));
            updateDigest(digest, String.valueOf(minLiteratureCount));
            updateDigest(digest, String.valueOf(scoreThreshold));
            updateDigest(digest, String.valueOf(maxRounds));
            updateDigest(digest, String.valueOf(innerMaxAttempts));
            updateDigest(digest, file.getOriginalFilename());
            updateDigest(digest, file.getBytes());
            if (bibFile != null && !bibFile.isEmpty()) {
                updateDigest(digest, bibFile.getOriginalFilename());
                updateDigest(digest, bibFile.getBytes());
            } else {
                updateDigest(digest, "");
            }
            return hex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to build paper task idempotency key", ex);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private void updateDigest(MessageDigest digest, byte[] value) {
        digest.update(value);
        digest.update((byte) '\n');
    }

    private String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private void saveSourceArtifact(Long taskId, String type, String objectKey, String filename) {
        PaperTaskArtifact artifact = new PaperTaskArtifact(taskId, type, objectKey, 1);
        artifact.setMetadataJson("{\"filename\":\"" + json(filename) + "\"}");
        artifactRepository.save(artifact);
    }

    private String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Transactional(readOnly = true)
    public List<PaperTaskHistoryResponse> listTasks(Long userId) {
        List<PaperTask> userTasks = paperTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (userTasks.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = userTasks.stream().map(PaperTask::getId).toList();
        Map<Long, List<PaperTaskArtifact>> artifactsByTask = artifactRepository.findByTaskIdInOrderByCreatedAt(taskIds).stream()
                .collect(Collectors.groupingBy(PaperTaskArtifact::getTaskId));
        return userTasks.stream()
                .map(task -> PaperTaskHistoryResponse.from(task, artifactsByTask.getOrDefault(task.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaperTaskResponse getTask(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
        return PaperTaskResponse.from(task, task.getLiteratureCount(), false);
    }

    @Transactional(readOnly = true)
    public Resource downloadResult(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
        List<PaperTaskArtifact> resultArtifacts = downloadableArtifacts(taskId);
        if (!resultArtifacts.isEmpty()) {
            return new ByteArrayResource(zipArtifacts(resultArtifacts));
        }
        if (task.getFinalObjectKey() != null && !task.getFinalObjectKey().isBlank()) {
            return new ByteArrayResource(paperStorageService.read(task.getFinalObjectKey()));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paper result not ready");
    }

    public boolean hasDownloadableResult(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
        if (!downloadableArtifacts(taskId).isEmpty()) {
            return true;
        }
        return task.getFinalObjectKey() != null && !task.getFinalObjectKey().isBlank();
    }

    public String downloadFilename(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
        String base = task.getSourceFilename() == null || task.getSourceFilename().isBlank()
                ? "paper-result"
                : task.getSourceFilename().replaceAll("\\.[^.]+$", "");
        if (!downloadableArtifacts(taskId).isEmpty()) {
            return base + "-artifacts.zip";
        }
        return task.getSourceFilename() == null || task.getSourceFilename().isBlank() ? "polished.tex" : task.getSourceFilename();
    }

    public String downloadContentType(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
        if (!downloadableArtifacts(taskId).isEmpty()) {
            return "application/zip";
        }
        return "application/x-tex; charset=UTF-8";
    }

    private List<PaperTaskArtifact> downloadableArtifacts(Long taskId) {
        Map<String, PaperTaskArtifact> latestByType = artifactRepository.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> downloadableArtifactTypes().contains(artifact.getType()))
                .filter(artifact -> PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()))
                .collect(Collectors.toMap(
                        PaperTaskArtifact::getType,
                        artifact -> artifact,
                        (left, right) -> right,
                        java.util.LinkedHashMap::new
                ));
        return latestByType.values().stream()
                .toList();
    }

    private byte[] zipArtifacts(List<PaperTaskArtifact> artifacts) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                Set<String> usedEntryNames = new HashSet<>();
                for (PaperTaskArtifact artifact : artifacts) {
                    String entryName = uniqueZipEntryName(filenameForArtifact(artifact), usedEntryNames);
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(paperStorageService.read(artifact.getObjectKey()));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to zip paper result artifacts", ex);
        }
    }

    private String uniqueZipEntryName(String filename, Set<String> usedEntryNames) {
        if (usedEntryNames.add(filename.toLowerCase(Locale.ROOT))) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int index = 2; ; index++) {
            String candidate = base + "-" + index + extension;
            if (usedEntryNames.add(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    private String filenameForArtifact(PaperTaskArtifact artifact) {
        String suffix = artifact.getVersion() == null || artifact.getVersion() <= 1 ? "" : "-v" + artifact.getVersion();
        return switch (artifact.getType()) {
            case "polished_tex" -> "polished" + suffix + ".tex";
            case "suggested_bib" -> metadataFilename(artifact, "suggested" + suffix + ".bib");
            case "suggested_bib_novel" -> "suggested-novel" + suffix + ".bib";
            case "review_report" -> "review-report" + suffix + ".md";
            case "retrieved_literature_json" -> "retrieved-literature" + suffix + ".json";
            case "retrieved_literature_md" -> "retrieved-literature" + suffix + ".md";
            case "source_bib" -> metadataFilename(artifact, "source-bibliography" + suffix + ".bib");
            default -> artifact.getType() + suffix + ".txt";
        };
    }

    private String metadataFilename(PaperTaskArtifact artifact, String fallback) {
        String metadata = artifact.getMetadataJson();
        if (metadata == null || metadata.isBlank()) {
            return fallback;
        }
        String marker = "\"filename\":\"";
        int start = metadata.indexOf(marker);
        if (start < 0) {
            return fallback;
        }
        start += marker.length();
        int end = metadata.indexOf('"', start);
        if (end <= start) {
            return fallback;
        }
        String filename = metadata.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\").trim();
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            return fallback;
        }
        return filename;
    }

    private Set<String> downloadableArtifactTypes() {
        return Set.of("polished_tex", "suggested_bib", "suggested_bib_novel", "review_report", "retrieved_literature_json", "retrieved_literature_md");
    }

    private String resolveTitle(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "unnamed paper task";
        }
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }
}
