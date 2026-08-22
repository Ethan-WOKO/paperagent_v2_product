package com.yanban.paper.service;

import com.yanban.core.agent.AgentTaskEventCreateRequest;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskEventTypes;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.core.agent.AgentTaskStatus;
import com.yanban.core.agent.AgentTaskUpsertRequest;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskClarification;
import com.yanban.paper.domain.PaperTaskClarificationRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskRound;
import com.yanban.paper.domain.PaperTaskRoundRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexParserService;
import com.yanban.paper.latex.LatexRoleRecognitionService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.RecognizedSectionRole;
import com.yanban.paper.latex.RoleRecognitionResult;
import com.yanban.paper.literature.LiteratureSearchResult;
import com.yanban.paper.literature.LiteratureService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaperOrchestrator.class);
    static final String STATUS_RUNNING = AgentTaskStatus.RUNNING.value();
    static final String STATUS_PAUSED = "PAUSED";
    static final String STATUS_WAITING_INPUT = AgentTaskStatus.WAITING_INPUT.value();
    static final String STATUS_COMPLETED = AgentTaskStatus.COMPLETED.value();
    static final String STATUS_FAILED = AgentTaskStatus.FAILED.value();
    static final String STATUS_CANCEL_REQUESTED = AgentTaskStatus.CANCEL_REQUESTED.value();
    static final String STATUS_CANCELLING = AgentTaskStatus.CANCELLING.value();
    static final String STATUS_CANCELLED = AgentTaskStatus.CANCELLED.value();
    static final String STATUS_STOPPED = AgentTaskStatus.STOPPED.value();
    static final String STAGE_CANCELLED = "CANCELLED";

    private final PaperTaskRepository tasks;
    private final PaperTaskRoundRepository rounds;
    private final PaperSectionRepository sections;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperTaskClarificationRepository clarifications;
    private final PaperEventStreamService eventStreamService;
    private final PaperStorageService paperStorageService;
    private final LatexParserService latexParserService;
    private final LatexRoleRecognitionService roleRecognitionService;
    private final PaperClarificationService clarificationService;
    private final PaperResearchProfileService researchProfileService;
    private final PaperIntroductionAnalysisService introductionAnalysisService;
    private final LiteratureService literatureService;
    private final PaperGapAnalysisService gapAnalysisService;
    private final PaperSectionPolishService sectionPolishService;
    private final PaperAssembleService assembleService;
    private final PaperCitationClosureService citationClosureService;
    private final PaperGlobalReviewService globalReviewService;
    private final Executor paperTaskExecutor;
    private final Executor paperSectionPolishExecutor;
    private final AgentTaskEventRecorder taskEvents;
    private final AgentTaskRegistry agentTaskRegistry;
    private final Map<Long, ControlState> controlStates = new ConcurrentHashMap<>();
    private final java.util.Set<Long> runningTasks = ConcurrentHashMap.newKeySet();

    public PaperOrchestrator(PaperTaskRepository tasks,
                             PaperTaskRoundRepository rounds,
                             PaperSectionRepository sections,
                             PaperTaskArtifactRepository artifacts,
                             PaperTaskClarificationRepository clarifications,
                             PaperEventStreamService eventStreamService,
                             PaperStorageService paperStorageService,
                             LatexParserService latexParserService,
                             LatexRoleRecognitionService roleRecognitionService,
                             PaperClarificationService clarificationService,
                             PaperResearchProfileService researchProfileService,
                             PaperIntroductionAnalysisService introductionAnalysisService,
                             LiteratureService literatureService,
                             PaperGapAnalysisService gapAnalysisService,
                             PaperSectionPolishService sectionPolishService,
                             PaperAssembleService assembleService,
                             @Qualifier("paperTaskExecutor") Executor paperTaskExecutor,
                             AgentTaskEventRecorder taskEvents) {
        this(tasks,
                rounds,
                sections,
                artifacts,
                clarifications,
                eventStreamService,
                paperStorageService,
                latexParserService,
                roleRecognitionService,
                clarificationService,
                researchProfileService,
                introductionAnalysisService,
                literatureService,
                gapAnalysisService,
                sectionPolishService,
                assembleService,
                null,
                null,
                paperTaskExecutor,
                Runnable::run,
                taskEvents,
                null);
    }

    public PaperOrchestrator(PaperTaskRepository tasks,
                             PaperTaskRoundRepository rounds,
                             PaperSectionRepository sections,
                             PaperTaskArtifactRepository artifacts,
                             PaperTaskClarificationRepository clarifications,
                             PaperEventStreamService eventStreamService,
                             PaperStorageService paperStorageService,
                             LatexParserService latexParserService,
                             LatexRoleRecognitionService roleRecognitionService,
                             PaperClarificationService clarificationService,
                             PaperResearchProfileService researchProfileService,
                             PaperIntroductionAnalysisService introductionAnalysisService,
                             LiteratureService literatureService,
                             PaperGapAnalysisService gapAnalysisService,
                             PaperSectionPolishService sectionPolishService,
                             PaperAssembleService assembleService,
                             @Qualifier("paperTaskExecutor") Executor paperTaskExecutor,
                             AgentTaskEventRecorder taskEvents,
                             AgentTaskRegistry agentTaskRegistry) {
        this(tasks, rounds, sections, artifacts, clarifications, eventStreamService, paperStorageService,
                latexParserService, roleRecognitionService, clarificationService, researchProfileService,
                introductionAnalysisService, literatureService, gapAnalysisService, sectionPolishService,
                assembleService, null, null, paperTaskExecutor, Runnable::run, taskEvents, agentTaskRegistry);
    }

    @Autowired
    public PaperOrchestrator(PaperTaskRepository tasks,
                             PaperTaskRoundRepository rounds,
                             PaperSectionRepository sections,
                             PaperTaskArtifactRepository artifacts,
                             PaperTaskClarificationRepository clarifications,
                             PaperEventStreamService eventStreamService,
                             PaperStorageService paperStorageService,
                             LatexParserService latexParserService,
                             LatexRoleRecognitionService roleRecognitionService,
                             PaperClarificationService clarificationService,
                             PaperResearchProfileService researchProfileService,
                             PaperIntroductionAnalysisService introductionAnalysisService,
                             LiteratureService literatureService,
                             PaperGapAnalysisService gapAnalysisService,
                             PaperSectionPolishService sectionPolishService,
                             PaperAssembleService assembleService,
                             PaperCitationClosureService citationClosureService,
                             PaperGlobalReviewService globalReviewService,
                             @Qualifier("paperTaskExecutor") Executor paperTaskExecutor,
                             @Qualifier("paperSectionPolishExecutor") Executor paperSectionPolishExecutor,
                             AgentTaskEventRecorder taskEvents,
                             AgentTaskRegistry agentTaskRegistry) {
        this.tasks = tasks;
        this.rounds = rounds;
        this.sections = sections;
        this.artifacts = artifacts;
        this.clarifications = clarifications;
        this.eventStreamService = eventStreamService;
        this.paperStorageService = paperStorageService;
        this.latexParserService = latexParserService;
        this.roleRecognitionService = roleRecognitionService;
        this.clarificationService = clarificationService;
        this.researchProfileService = researchProfileService;
        this.introductionAnalysisService = introductionAnalysisService;
        this.literatureService = literatureService;
        this.gapAnalysisService = gapAnalysisService;
        this.sectionPolishService = sectionPolishService;
        this.assembleService = assembleService;
        this.citationClosureService = citationClosureService;
        this.globalReviewService = globalReviewService;
        this.paperTaskExecutor = paperTaskExecutor;
        this.paperSectionPolishExecutor = paperSectionPolishExecutor;
        this.taskEvents = taskEvents;
        this.agentTaskRegistry = agentTaskRegistry;
    }

    public void startTask(Long taskId) {
        controlStates.putIfAbsent(taskId, new ControlState());
        if (!runningTasks.add(taskId)) {
            log.info("Paper task {} is already running, skip duplicate start", taskId);
            return;
        }
        paperTaskExecutor.execute(() -> {
            try {
                runTask(taskId);
            } finally {
                runningTasks.remove(taskId);
            }
        });
    }

    @Transactional
    public void pause(Long userId, Long taskId) {
        PaperTask task = getOwnedTask(userId, taskId);
        if (isTerminalStatus(task.getStatus()) || isCancellingStatus(task.getStatus())) {
            return;
        }
        controlStates.computeIfAbsent(taskId, key -> new ControlState()).paused = true;
        task.setStatus(STATUS_PAUSED);
        task.setCurrentStage(task.getCurrentStage() == null ? STATUS_PAUSED : task.getCurrentStage());
        syncUnifiedTask(task);
        eventStreamService.publish(PaperSseEvent.of("paused", taskId, "任务已暂停", task.getCurrentStage()));
        recordEvent(task, AgentTaskEventTypes.TASK_PAUSED, task.getCurrentStage(), STATUS_PAUSED, "论文润色任务已暂停");
    }

    @Transactional
    public void resume(Long userId, Long taskId) {
        PaperTask task = getOwnedTask(userId, taskId);
        if (isTerminalStatus(task.getStatus()) || isCancellingStatus(task.getStatus())) {
            return;
        }
        ControlState state = controlStates.computeIfAbsent(taskId, key -> new ControlState());
        state.paused = false;
        task.setStatus(STATUS_RUNNING);
        syncUnifiedTask(task);
        eventStreamService.publish(PaperSseEvent.of("log", taskId, "任务继续执行", task.getCurrentStage()));
        recordEvent(task, AgentTaskEventTypes.TASK_RESUMED, task.getCurrentStage(), STATUS_RUNNING, "论文润色任务继续执行");
    }

    @Transactional
    public void stop(Long userId, Long taskId) {
        stop(userId, taskId, null);
    }

    @Transactional
    public void stop(Long userId, Long taskId, String cancelReason) {
        PaperTask task = getOwnedTask(userId, taskId);
        if (isTerminalStatus(task.getStatus())) {
            return;
        }
        ControlState state = controlStates.computeIfAbsent(taskId, key -> new ControlState());
        state.stopped = true;
        state.paused = false;
        state.cancellationReason = trimToNull(cancelReason);
        String currentStage = task.getCurrentStage() == null ? STATUS_CANCEL_REQUESTED : task.getCurrentStage();
        eventStreamService.publish(PaperSseEvent.of("cancel_requested", taskId, "任务停止请求已受理，正在等待安全检查点", currentStage));
        recordEvent(task, AgentTaskEventTypes.TASK_CANCEL_REQUESTED, currentStage, STATUS_CANCEL_REQUESTED, "论文润色任务停止请求已受理");
        if (runningTasks.contains(taskId)) {
            task.setStatus(STATUS_CANCEL_REQUESTED);
            task.setCurrentStage(currentStage);
            task.setErrorMessage(null);
            syncUnifiedTask(task);
            return;
        }
        markGeneratedArtifactsPartial(task);
        task.setFinalObjectKey(null);
        task.setStatus(STATUS_CANCELLED);
        task.setCurrentStage(STAGE_CANCELLED);
        task.setErrorMessage("任务已取消");
        syncUnifiedTask(task);
        eventStreamService.publish(PaperSseEvent.of("cancelled", taskId, "任务已取消", STAGE_CANCELLED));
        recordEvent(task, AgentTaskEventTypes.TASK_CANCELLED, STAGE_CANCELLED, STATUS_CANCELLED, "论文润色任务已取消");
    }

    private void runTask(Long taskId) {
        try {
            checkpoint(taskId);
            PaperTask task = tasks.findById(taskId).orElseThrow();
            try (PaperModelExecutionContext.Scope ignored = PaperModelExecutionContext.open(task.getUserId())) {
            transition(taskId, STATUS_RUNNING, "PARSE", null);
            publishProgress("log", taskId, "开始读取 LaTeX 源文件", "PARSE", null, null, null, null, null, 5);
            checkpoint(taskId);

            String texContent = new String(readStorageWithRetry(task.getObjectKey(), "source_tex", taskId), StandardCharsets.UTF_8);
            Map<String, String> bibFiles = readBibFiles(taskId);
            LatexDocument document = latexParserService.parse(task.getMainEntry(), texContent, bibFiles);
            checkpoint(taskId);
            persistRound(taskId, 1, "PARSE", "COMPLETED", task.getMainEntry(), "sections=" + document.sections().size(), "latex parse");
            publishProgress("parse_done", taskId, "LaTeX 解析完成：识别到 " + document.sections().size() + " 个章节", "PARSE", 0, document.sections().size(), null, null, null, 20);

            transition(taskId, STATUS_RUNNING, "STRUCTURE_CHECK", null);
            checkpoint(taskId);
            RoleRecognitionResult roles = roleRecognitionService.recognize(document);
            saveSections(task, document, roles);
            publishProgress("sections", taskId, "章节角色识别完成", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 35);
            checkpoint(taskId);

            if (!roles.clarifications().isEmpty()) {
                List<PaperTaskClarification> existingClarifications = clarifications.findByTaskIdOrderByCreatedAtAsc(taskId);
                boolean hasPendingClarification = existingClarifications.stream().anyMatch(item -> "PENDING".equals(item.getStatus()));
                if (existingClarifications.isEmpty()) {
                    clarificationService.createPendingClarifications(taskId, roles.clarifications());
                    if (roles.clarifications().stream().anyMatch(item -> item.blocking())) {
                        persistRound(taskId, 2, "STRUCTURE_CHECK", STATUS_WAITING_INPUT, "clarifications", "pending=" + roles.clarifications().size(), "waiting user confirmation");
                        publishProgress("clarification_needed", taskId, "需要确认论文结构后再继续", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 40);
                        return;
                    }
                } else if (hasPendingClarification) {
                    transition(taskId, STATUS_WAITING_INPUT, "STRUCTURE_CHECK", null);
                    publishProgress("clarification_needed", taskId, "仍有结构确认问题待回答", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 40);
                    return;
                } else {
                    publishProgress("clarification_resolved", taskId, "结构确认已完成，继续处理", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 45);
                }
            }

            transition(taskId, STATUS_RUNNING, "PROFILE", null);
            checkpoint(taskId);
            publishProgress("profile_start", taskId, "开始抽取研究画像", "PROFILE", 0, 1, null, null, null, 50);
            ResearchProfileResult profile = researchProfileService.generateAndSave(taskId, document, task.getTargetLanguage());
            introductionAnalysisService.analyzeAndSave(taskId, document, task.getTargetLanguage());
            checkpoint(taskId);
            persistRound(taskId, 3, "PROFILE", "COMPLETED", "sections=" + document.sections().size(), "keywords=" + profile.keywords(), profile.degraded() ? "degraded" : "model");
            publishProgress("profile_complete", taskId, "研究画像与引言论证骨架完成", "PROFILE", 1, 1, null, null, null, 58);

            transition(taskId, STATUS_RUNNING, "RETRIEVE", null);
            checkpoint(taskId);
            publishProgress("retrieve_start", taskId, "按引言 citation slots 检索真实候选文献", "RETRIEVE", 0, 1, null, null, null, 60);
            int literatureLimit = literatureLimit(task);
            int literatureMinLimit = literatureMinLimit(task, literatureLimit);
            int perQueryLimit = Math.max(5, Math.min(20, (int) Math.ceil(literatureLimit / 2.0)));
            List<LiteratureSearchResult> selectedLiterature = literatureService.retrieveForTask(
                    taskId,
                    profile,
                    perQueryLimit,
                    literatureMinLimit,
                    literatureLimit,
                    String.join("\n\n", bibFiles.values()),
                    (completed, total, cardId, title) -> publishProgress(
                            "literature_card_analysis",
                            taskId,
                            completed == 0 ? "Preparing literature card analysis" : "Literature card analysis " + completed + "/" + total,
                            "RETRIEVE",
                            completed,
                            total,
                            title,
                            null,
                            null,
                            total <= 0 ? 60 : 60 + Math.min(8, (int) Math.round((completed * 8.0) / total))
                    )
            );
            checkpoint(taskId);
            persistRound(taskId, 4, "RETRIEVE", "COMPLETED", "queries=" + profile.keywords(), "selected=" + selectedLiterature.size(), null);
            publishProgress("retrieve_complete", taskId, "文献检索完成，已选择 " + selectedLiterature.size() + " 篇候选文献", "RETRIEVE", selectedLiterature.size(), literatureLimit, null, null, null, 70);
            if (isLiteratureOnly(task)) {
                transition(taskId, STATUS_RUNNING, "ASSEMBLE", null);
                checkpoint(taskId);
                publishProgress("assemble_start", taskId, "仅文献推荐模式：生成 recommended bib 与检索报告", "ASSEMBLE", selectedLiterature.size(), literatureLimit, null, null, null, 92);
                assembleService.assemble(taskId, document, false);
                recordTaskEvent(taskId, AgentTaskEventTypes.TASK_COMPLETED, "COMPLETE", STATUS_COMPLETED, "论文润色任务已完成");
                checkpoint(taskId);
                publishProgress("complete", taskId, "文献推荐已完成：未执行 Gap 分析、章节润色和全文改写", "COMPLETE", selectedLiterature.size(), 8, null, null, null, 100);
                return;
            }

            transition(taskId, STATUS_RUNNING, "GAP_ANALYSIS", null);
            checkpoint(taskId);
            publishProgress("gap_start", taskId, "开始生成 Gap 分析与建议", "GAP_ANALYSIS", 0, 1, null, null, null, 72);
            List<GapSuggestionResult> gapSuggestions = gapAnalysisService.generateAndSave(
                    taskId, structureSummary(document, roles), task.getTargetLanguage(), document);
            if (gapSuggestions.isEmpty() && gapAnalysisService.isDegradedWithoutSuggestions(taskId)) {
                throw new IllegalStateException("Gap analysis returned no usable model response after retry; selected literature was preserved for retry.");
            }
            LatexDocument markedDocument = gapAnalysisService.markCitationSlots(document, gapSuggestions);
            if (markedDocument != null) document = markedDocument;
            checkpoint(taskId);
            persistRound(taskId, 5, "GAP_ANALYSIS", "COMPLETED", "selectedLiterature=" + selectedLiterature.size(), "suggestions=" + gapSuggestions.size(), null);
            publishProgress("gap_complete", taskId, "Gap 分析完成，生成 " + gapSuggestions.size() + " 条建议", "GAP_ANALYSIS", gapSuggestions.size(), Math.max(1, gapSuggestions.size()), null, null, null, 80);

            transition(taskId, STATUS_RUNNING, "POLISH", null);
            checkpoint(taskId);
            publishProgress("polish_start", taskId, "开始分章润色", "POLISH", 0, document.sections().size(), null, null, null, 82);
            int polishedCount = polishSections(task, document);
            checkpoint(taskId);
            persistRound(taskId, 6, "POLISH", "COMPLETED", "sections=" + document.sections().size(), "processed=" + polishedCount, null);
            publishProgress("polish_complete", taskId, "分章润色完成，处理 " + polishedCount + " 个章节", "POLISH", polishedCount, document.sections().size(), null, null, null, 90);

            transition(taskId, STATUS_RUNNING, "GLOBAL_REVIEW", null);
            checkpoint(taskId);
            List<PaperSection> polishedSections = sections.findByTaskIdOrderByOrderIndexAsc(taskId);
            String finalDraftTex = assembleService.buildDraft(document, polishedSections);
            LatexDocument finalDraftDocument = latexParserService.parse(task.getMainEntry(), finalDraftTex, Map.of());
            publishProgress("global_review_start", taskId, "Starting whole-paper consistency review", "GLOBAL_REVIEW", 0, 1, null, null, null, 91);
            if (globalReviewService != null) {
                globalReviewService.reviewAndSave(task, finalDraftDocument, finalDraftTex, polishedSections);
                polishedSections = sections.findByTaskIdOrderByOrderIndexAsc(taskId);
                finalDraftTex = assembleService.buildDraft(document, polishedSections);
                finalDraftDocument = latexParserService.parse(task.getMainEntry(), finalDraftTex, Map.of());
            }
            publishProgress("global_review_complete", taskId, "Whole-paper consistency review completed", "GLOBAL_REVIEW", 1, 1, null, null, null, 92);
            if (citationClosureService != null) {
                publishProgress("citation_closure_start", taskId,
                        "Checking unresolved Introduction citations with critic-editor review",
                        "GLOBAL_REVIEW", 0, 1, null, null, null, 93);
                PaperCitationClosureService.ClosureResult closureResult = citationClosureService.close(taskId, finalDraftDocument);
                checkpoint(taskId);
                publishProgress("citation_closure_complete", taskId,
                        "Citation review completed: " + closureResult.acceptedCount()
                                + " applied, " + closureResult.reportOnlyCount() + " report only",
                        "GLOBAL_REVIEW", 1, 1, null, null, null, 94);
            }

            transition(taskId, STATUS_RUNNING, "ASSEMBLE", null);
            checkpoint(taskId);
            publishProgress("assemble_start", taskId, "开始生成完整产物", "ASSEMBLE", document.sections().size(), document.sections().size(), null, null, null, 92);
            assembleService.assemble(taskId, document, true);
            recordTaskEvent(taskId, AgentTaskEventTypes.TASK_COMPLETED, "COMPLETE", STATUS_COMPLETED, "论文润色任务已完成");
            checkpoint(taskId);
            publishProgress("complete", taskId, "论文任务已完成：已生成润色文本、推荐文献与审查报告", "COMPLETE", document.sections().size(), document.sections().size(), null, null, null, 100);
            }
        } catch (TaskStoppedException ex) {
            transitionCancelled(taskId, ex.getMessage());
        } catch (Exception | LinkageError ex) {
            if (isCancellationRequested(taskId)) {
                transitionCancelled(taskId, "任务已取消");
                return;
            }
            log.error("Paper task {} failed", taskId, ex);
            transition(taskId, STATUS_FAILED, STATUS_FAILED, rootMessage(ex));
            publish("error", taskId, "论文任务失败: " + rootMessage(ex), STATUS_FAILED);
        }
    }

    private boolean isLiteratureOnly(PaperTask task) {
        return false;
    }

    private int literatureLimit(PaperTask task) {
        Integer value = task.getLiteratureCount();
        return value == null ? 20 : Math.max(1, Math.min(100, value));
    }

    private int literatureMinLimit(PaperTask task, int maxLimit) {
        Integer value = task.getLiteratureMinCount();
        return value == null ? Math.min(8, maxLimit) : Math.max(1, Math.min(maxLimit, value));
    }

    private Map<String, String> readBibFiles(Long taskId) {
        Map<String, String> bibFiles = new LinkedHashMap<>();
        artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> "source_bib".equals(artifact.getType()))
                .forEach(artifact -> {
                    try {
                        bibFiles.put("refs.bib", new String(readStorageWithRetry(artifact.getObjectKey(), "source_bib", taskId), StandardCharsets.UTF_8));
                    } catch (Exception ex) {
                        log.warn("Skip unreadable bib artifact {} for paper task {}", artifact.getObjectKey(), taskId, ex);
                        publishProgress("bib_read_skipped", taskId, "参考文献文件读取失败，已按无 bib 模式继续：" + rootMessage(ex), "PARSE", null, null, null, null, null, null);
                    }
                });
        return bibFiles;
    }

    private byte[] readStorageWithRetry(String objectKey, String label, Long taskId) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                byte[] bytes = paperStorageService.read(objectKey);
                if (bytes.length > 0) {
                    return bytes;
                }
                last = new IllegalStateException(label + " is empty");
            } catch (RuntimeException ex) {
                last = ex;
            }
            log.warn("Read {} for paper task {} failed on attempt {}/3, objectKey={}", label, taskId, attempt, objectKey, last);
            sleep(300L * attempt);
        }
        throw new IllegalStateException("读取论文" + label + "失败，请重新上传或检查本地/MinIO 存储", last);
    }

    private String structureSummary(LatexDocument document, RoleRecognitionResult roles) {
        StringBuilder builder = new StringBuilder();
        builder.append("Sections:\n");
        for (LatexSection section : document.sections()) {
            builder.append("- [").append(section.orderIndex()).append("] ")
                    .append(section.title()).append(" role=").append(section.role()).append('\n');
        }
        if (!roles.clarifications().isEmpty()) {
            builder.append("Clarifications:\n");
            roles.clarifications().forEach(item -> builder.append("- ").append(item.message()).append(" default=").append(item.defaultOption()).append('\n'));
        }
        return builder.toString();
    }

    private int polishSections(PaperTask task, LatexDocument document) {
        Long taskId = task.getId();
        int total = document.sections().size();
        int scoreThreshold = scoreThreshold(task);
        int maxRounds = maxRounds(task);
        int innerMaxAttempts = innerMaxAttempts(task);
        AtomicInteger completed = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>(total);
        for (LatexSection section : document.sections()) {
            PaperSectionPolishService.SectionContext context = sectionContext(document, section);
            futures.add(CompletableFuture.runAsync(
                    () -> polishSection(task, section, context, scoreThreshold, maxRounds,
                            innerMaxAttempts, completed, total),
                    paperSectionPolishExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return completed.get();
    }

    private void polishSection(PaperTask task,
                               LatexSection section,
                               PaperSectionPolishService.SectionContext context,
                               int scoreThreshold,
                               int maxRounds,
                               int innerMaxAttempts,
                               AtomicInteger completed,
                               int total) {
        Long taskId = task.getId();
        checkpoint(taskId);
        publishProgress("section_polish_start", taskId, "润色章节：" + section.title(), "POLISH",
                completed.get(), total, section.title(), 0, maxRounds, null);
        SectionPolishResult sectionResult = null;
        try (PaperModelExecutionContext.Scope ignored = PaperModelExecutionContext.open(task.getUserId())) {
            sectionResult = sectionPolishService.polishSection(taskId, section, task.getTargetLanguage(),
                    scoreThreshold, maxRounds, innerMaxAttempts, context);
        } catch (Exception ex) {
            log.warn("Skip polishing section {} of task {} due to error", section.title(), taskId, ex);
            markSectionFailed(task, section, ex, maxRounds, innerMaxAttempts);
            publishProgress("section_polish_skipped", taskId, "章节润色失败，已保留原文：" + section.title(),
                    "POLISH", completed.get(), total, section.title(), maxRounds, maxRounds, null);
        }
        int processed = completed.incrementAndGet();
        int completedAttempts = sectionResult == null ? maxRounds : sectionResult.attempts();
        publishProgress("section_polish_complete", taskId, "章节处理完成：" + section.title(), "POLISH",
                processed, total, section.title(), completedAttempts, maxRounds, null);
    }

    private PaperSectionPolishService.SectionContext sectionContext(LatexDocument document, LatexSection current) {
        String outline = document.sections().stream()
                .map(section -> section.orderIndex() + ": " + section.title() + " [" + section.role() + "]")
                .collect(Collectors.joining("\n"));
        int index = document.sections().indexOf(current);
        String previous = index > 0 ? truncateEnding(document.sections().get(index - 1).rawText(), 900) : "None (first section).";
        String next = index >= 0 && index + 1 < document.sections().size()
                ? truncate(document.sections().get(index + 1).rawText(), 900) : "None (last section).";
        return new PaperSectionPolishService.SectionContext(outline, previous, next,
                "Preserve the notation already present in the section and its neighboring sections.",
                "Use the gap analysis and evidence already stored for this task; do not invent new claims.");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + " ...";
    }

    private String truncateEnding(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : "... " + normalized.substring(normalized.length() - maxLength);
    }

    private int scoreThreshold(PaperTask task) {
        Integer value = task.getScoreThreshold();
        return value == null ? 80 : Math.max(0, Math.min(100, value));
    }

    private int maxRounds(PaperTask task) {
        Integer value = task.getMaxRounds();
        return value == null ? 3 : Math.max(1, Math.min(20, value));
    }

    private int innerMaxAttempts(PaperTask task) {
        Integer value = task.getInnerMaxAttempts();
        return value == null ? 2 : Math.max(1, Math.min(20, value));
    }

    @Transactional
    protected void markSectionFailed(PaperTask task, LatexSection latexSection, Exception ex, int maxRounds, int innerMaxAttempts) {
        sections.findByTaskIdOrderByOrderIndexAsc(task.getId()).stream()
                .filter(section -> section.getOrderIndex().equals(latexSection.orderIndex()))
                .findFirst()
                .ifPresent(section -> {
                    section.setPolishStatus("FAILED_KEEP_ORIGINAL");
                    section.setReviewJson("""
                            {"reasonCode":"section_polish_exception","reasonMessage":"%s","score":0.0,"passed":false,"attempts":0,"maxRounds":%d,"innerMaxAttempts":%d,"keptOriginal":true,"protectionTriggered":false,"lintIssues":[]}
                            """.formatted(json(rootMessage(ex)), maxRounds, innerMaxAttempts).trim());
                    section.setDiffJson("{}");
                    sections.save(section);
                });
    }

    @Transactional
    protected void saveSections(PaperTask task, LatexDocument document, RoleRecognitionResult roles) {
        sections.deleteAll(sections.findByTaskIdOrderByOrderIndexAsc(task.getId()));
        Map<Integer, RecognizedSectionRole> roleByOrder = roles.sectionRoles().stream()
                .collect(Collectors.toMap(RecognizedSectionRole::sectionOrderIndex, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        for (LatexSection latexSection : document.sections()) {
            RecognizedSectionRole recognized = roleByOrder.get(latexSection.orderIndex());
            String originalObjectKey = paperStorageService.storeArtifact(
                    task.getUserId(),
                    "section_original",
                    "section-" + latexSection.orderIndex() + ".tex",
                    latexSection.rawText().getBytes(StandardCharsets.UTF_8),
                    "application/x-tex; charset=UTF-8"
            );
            PaperSection section = new PaperSection(
                    task.getId(),
                    document.sourcePath(),
                    latexSection.orderIndex(),
                    latexSection.level(),
                    latexSection.title(),
                    recognized == null ? latexSection.role().name() : recognized.role().name(),
                    recognized == null ? 0.5 : recognized.confidence(),
                    recognized == null ? "parser" : recognized.source(),
                    latexSection.startOffset(),
                    latexSection.endOffset()
            );
            section.setOriginalObjectKey(originalObjectKey);
            section.setPolishStatus("PENDING");
            sections.save(section);
        }
    }

    void checkpoint(Long taskId) {
        ControlState state = controlStates.computeIfAbsent(taskId, key -> new ControlState());
        throwIfCancellationRequested(taskId, state);
        while (state.paused) {
            sleep(200);
            throwIfCancellationRequested(taskId, state);
        }
        sleep(200);
        throwIfCancellationRequested(taskId, state);
    }

    @Transactional
    protected void transition(Long taskId, String status, String stage, String errorMessage) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        if (isCancellingStatus(task.getStatus()) && !isCancellingStatus(status)) {
            return;
        }
        task.setStatus(status);
        task.setCurrentStage(stage);
        task.setErrorMessage(errorMessage);
        tasks.save(task);
        syncUnifiedTask(task);
        String eventType = STATUS_FAILED.equals(status) ? AgentTaskEventTypes.TASK_FAILED : AgentTaskEventTypes.STAGE_CHANGED;
        String message = STATUS_FAILED.equals(status) ? errorMessage : "论文润色任务进入阶段: " + stage;
        recordEvent(task, eventType, stage, status, message);
    }

    @Transactional
    protected void transitionCancelled(Long taskId, String message) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        if (STATUS_COMPLETED.equals(task.getStatus())) {
            return;
        }
        markGeneratedArtifactsPartial(task);
        task.setFinalObjectKey(null);
        task.setStatus(STATUS_CANCELLED);
        task.setCurrentStage(STAGE_CANCELLED);
        task.setErrorMessage(message);
        tasks.save(task);
        syncUnifiedTask(task);
        publish("cancelled", taskId, message, STAGE_CANCELLED);
        recordEvent(task, AgentTaskEventTypes.TASK_CANCELLED, STAGE_CANCELLED, STATUS_CANCELLED, message);
    }

    private void markGeneratedArtifactsPartial(PaperTask task) {
        List<PaperTaskArtifact> changedArtifacts = artifacts.findByTaskIdOrderByCreatedAt(task.getId()).stream()
                .filter(this::isGeneratedArtifact)
                .filter(artifact -> PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()))
                .peek(artifact -> artifact.setArtifactStatus(PaperTaskArtifact.STATUS_PARTIAL))
                .toList();
        if (changedArtifacts.isEmpty()) {
            return;
        }
        artifacts.saveAll(changedArtifacts);
        for (PaperTaskArtifact artifact : changedArtifacts) {
            recordEvent(
                    task,
                    AgentTaskEventTypes.ARTIFACT_MARKED_PARTIAL,
                    task.getCurrentStage(),
                    task.getStatus(),
                    "Artifact marked partial after cancellation: " + artifact.getType() + " v" + artifact.getVersion()
            );
        }
    }

    private boolean isGeneratedArtifact(PaperTaskArtifact artifact) {
        return artifact.getType() != null && !artifact.getType().startsWith("source_");
    }

    @Transactional
    protected void persistRound(Long taskId, int roundNumber, String stage, String status,
                                String inputText, String outputText, String notes) {
        PaperTaskRound round = rounds.findByTaskIdAndRoundNumberAndStage(taskId, roundNumber, stage)
                .orElseGet(() -> new PaperTaskRound(taskId, roundNumber, stage, status, inputText, outputText, notes));
        round.setStatus(status);
        round.setInputText(inputText);
        round.setOutputText(outputText);
        round.setNotes(notes);
        rounds.save(round);
    }

    @Transactional
    protected void transitionComplete(Long taskId, String finalObjectKey) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        task.setFinalObjectKey(finalObjectKey);
        task.setStatus("COMPLETED");
        task.setCurrentStage("COMPLETE");
        task.setErrorMessage(null);
        tasks.save(task);
        syncUnifiedTask(task);
    }

    private void publish(String type, Long taskId, String message, String stage) {
        eventStreamService.publish(PaperSseEvent.of(type, taskId, message, stage));
    }

    private void publishProgress(String type,
                                 Long taskId,
                                 String message,
                                 String stage,
                                 Integer currentSection,
                                 Integer totalSections,
                                 String sectionTitle,
                                 Integer attempt,
                                 Integer maxAttempts,
                                 Integer progressPercent) {
        eventStreamService.publish(PaperSseEvent.progress(
                type,
                taskId,
                message,
                stage,
                currentSection,
                totalSections,
                sectionTitle,
                attempt,
                maxAttempts,
                progressPercent
        ));
        if (taskEvents != null) {
            tasks.findById(taskId).ifPresent(task -> taskEvents.recordSafely(new AgentTaskEventCreateRequest(
                    AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                    taskId,
                    task.getUserId(),
                    AgentTaskEventTypes.TASK_PROGRESS,
                    stage,
                    task.getStatus(),
                    message,
                    progressPayload(type, currentSection, totalSections, sectionTitle, attempt, maxAttempts, progressPercent)
            )));
        }
    }

    private String progressPayload(String progressType,
                                   Integer currentSection,
                                   Integer totalSections,
                                   String sectionTitle,
                                   Integer attempt,
                                   Integer maxAttempts,
                                   Integer progressPercent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("progressType", progressType);
        if (currentSection != null) payload.put("currentSection", currentSection);
        if (totalSections != null) payload.put("totalSections", totalSections);
        if (sectionTitle != null && !sectionTitle.isBlank()) payload.put("sectionTitle", sectionTitle);
        if (attempt != null) payload.put("attempt", attempt);
        if (maxAttempts != null) payload.put("maxAttempts", maxAttempts);
        if (progressPercent != null) payload.put("progressPercent", progressPercent);
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(this.json(entry.getKey())).append("\":");
            if (entry.getValue() instanceof Number || entry.getValue() instanceof Boolean) {
                json.append(entry.getValue());
            } else {
                json.append('"').append(this.json(String.valueOf(entry.getValue()))).append('"');
            }
        }
        return json.append('}').toString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private PaperTask getOwnedTask(Long userId, Long taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
    }

    private void throwIfCancellationRequested(Long taskId, ControlState state) {
        if (!state.stopped || !markCancelling(taskId)) {
            return;
        }
        throw new TaskStoppedException("任务已取消");
    }

    private boolean markCancelling(Long taskId) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        if (isTerminalStatus(task.getStatus())) {
            return false;
        }
        if (!STATUS_CANCELLING.equals(task.getStatus())) {
            task.setStatus(STATUS_CANCELLING);
            task.setCurrentStage(task.getCurrentStage() == null ? STATUS_CANCELLING : task.getCurrentStage());
            task.setErrorMessage(null);
            tasks.save(task);
            syncUnifiedTask(task);
            publish("cancelling", taskId, "任务正在安全停止", task.getCurrentStage());
            recordEvent(task, AgentTaskEventTypes.TASK_CANCELLING, task.getCurrentStage(), STATUS_CANCELLING, "论文润色任务正在安全停止");
        }
        return true;
    }

    private void syncUnifiedTask(PaperTask task) {
        if (agentTaskRegistry == null) {
            return;
        }
        agentTaskRegistry.upsertSafely(new AgentTaskUpsertRequest(
                task.getUserId(),
                null,
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                AgentTaskRegistry.SOURCE_PAPER_TASK,
                task.getId(),
                task.getStatus(),
                "LONG_RUNNING_TOOL_TASK",
                null,
                task.getTitle(),
                task.getSourceFilename(),
                STATUS_COMPLETED.equals(task.getStatus()) ? 100 : null,
                task.getCurrentStage(),
                null,
                task.getErrorMessage(),
                cancellationReasonFor(task.getId()),
                0,
                0,
                STATUS_RUNNING.equals(task.getStatus()) || isCancellingStatus(task.getStatus()) ? task.getUpdatedAt() : null,
                isTerminalStatus(task.getStatus()) ? task.getUpdatedAt() : null
        ));
    }

    private boolean isCancellationRequested(Long taskId) {
        ControlState state = controlStates.get(taskId);
        if (state != null && state.stopped) {
            return true;
        }
        return tasks.findById(taskId)
                .map(task -> isCancellingStatus(task.getStatus()))
                .orElse(false);
    }

    private boolean isTerminalStatus(String status) {
        return AgentTaskStatus.isTerminal(status);
    }

    private boolean isCancellingStatus(String status) {
        return STATUS_CANCEL_REQUESTED.equals(status)
                || STATUS_CANCELLING.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    private void recordTaskEvent(Long taskId, String eventType, String stage, String status, String message) {
        tasks.findById(taskId).ifPresent(task -> recordEvent(task, eventType, stage, status, message));
    }

    private void recordEvent(PaperTask task, String eventType, String stage, String status, String message) {
        taskEvents.recordSafely(new AgentTaskEventCreateRequest(
                AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                task.getId(),
                task.getUserId(),
                eventType,
                stage,
                status,
                message,
                null
        ));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务线程被中断", ex);
        }
    }

    private String cancellationReasonFor(Long taskId) {
        ControlState state = controlStates.get(taskId);
        return state == null ? null : state.cancellationReason;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class ControlState {
        private volatile boolean paused;
        private volatile boolean stopped;
        private volatile String cancellationReason;
    }

    private static final class TaskStoppedException extends RuntimeException {
        private TaskStoppedException(String message) {
            super(message);
        }
    }
}
