package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.service.PaperOrchestrator;
import com.yanban.paper.service.PaperTaskService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaperTaskToolExecutorTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 101L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaperTaskRepository tasks;
    private PaperTaskArtifactRepository artifacts;
    private SuggestionRepository suggestions;
    private PaperTaskLiteratureRepository taskLiterature;
    private PaperTaskService paperTaskService;
    private PaperOrchestrator paperOrchestrator;
    private PaperTaskToolSupport support;

    @BeforeEach
    void setUp() {
        tasks = mock(PaperTaskRepository.class);
        artifacts = mock(PaperTaskArtifactRepository.class);
        suggestions = mock(SuggestionRepository.class);
        taskLiterature = mock(PaperTaskLiteratureRepository.class);
        paperTaskService = mock(PaperTaskService.class);
        paperOrchestrator = mock(PaperOrchestrator.class);
        support = new PaperTaskToolSupport(
                tasks,
                artifacts,
                suggestions,
                taskLiterature,
                paperTaskService,
                paperOrchestrator,
                objectMapper
        );
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void registersPaperTaskTools() {
        ToolRegistry registry = new ToolRegistry()
                .register(new PaperPolishStatusToolExecutor(support, objectMapper))
                .register(new PaperPolishResultToolExecutor(support, objectMapper))
                .register(new PaperTaskCancelToolExecutor(support, objectMapper));

        assertThat(registry.listToolNames())
                .containsExactlyInAnyOrder("paper_polish_status", "paper_polish_result", "paper_task_cancel");
        assertThat(registry.find("paper_polish_status").orElseThrow().definition().parameters().path("required").get(0).asText())
                .isEqualTo("taskId");
    }

    @Test
    void statusReturnsCurrentUsersTaskSummary() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        PaperTask task = task("RUNNING", "POLISH");
        PaperTaskArtifact artifact = artifact("review_report", "review-report.md");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(artifacts.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of(artifact));
        when(paperTaskService.hasDownloadableResult(USER_ID, TASK_ID)).thenReturn(true);

        ToolResult result = new PaperPolishStatusToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-1", "paper_polish_status", args(TASK_ID)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("taskId").asLong()).isEqualTo(TASK_ID);
        assertThat(result.output().path("status").asText()).isEqualTo("RUNNING");
        assertThat(result.output().path("downloadAvailable").asBoolean()).isTrue();
        assertThat(result.output().path("artifacts").get(0).path("filename").asText()).isEqualTo("review-report.md");
        assertThat(result.output().path("artifacts").get(0).path("artifactStatus").asText()).isEqualTo(PaperTaskArtifact.STATUS_COMPLETED);
        assertThat(result.output().path("artifacts").get(0).has("objectKey")).isFalse();
    }

    @Test
    void partialArtifactsAreNotReportedDownloadable() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        PaperTask task = task("CANCELLED", "CANCELLED");
        PaperTaskArtifact artifact = artifact("polished_tex", "polished.tex");
        artifact.setArtifactStatus(PaperTaskArtifact.STATUS_PARTIAL);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(artifacts.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of(artifact));
        when(paperTaskService.hasDownloadableResult(USER_ID, TASK_ID)).thenReturn(false);

        ToolResult result = new PaperPolishStatusToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-partial", "paper_polish_status", args(TASK_ID)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("downloadAvailable").asBoolean()).isFalse();
        assertThat(result.output().path("artifacts").get(0).path("artifactStatus").asText()).isEqualTo(PaperTaskArtifact.STATUS_PARTIAL);
        assertThat(result.output().path("artifacts").get(0).path("downloadable").asBoolean()).isFalse();
    }

    @Test
    void resultReturnsArtifactSuggestionAndLiteratureCounts() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        PaperTask task = task("COMPLETED", "COMPLETE");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(artifacts.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of(artifact("polished_tex", "polished.tex")));
        when(paperTaskService.hasDownloadableResult(USER_ID, TASK_ID)).thenReturn(true);
        when(paperTaskService.downloadFilename(USER_ID, TASK_ID)).thenReturn("main-artifacts.zip");
        when(paperTaskService.downloadContentType(USER_ID, TASK_ID)).thenReturn("application/zip");
        when(suggestions.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of(new Suggestion(TASK_ID, "ADVOCACY", "RelatedWork", "Add related work.")));
        when(taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(TASK_ID)).thenReturn(List.of(new PaperTaskLiterature(TASK_ID, 1L)));

        ToolResult result = new PaperPolishResultToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-2", "paper_polish_result", args(TASK_ID)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("terminal").asBoolean()).isTrue();
        assertThat(result.output().path("downloadUrl").asText()).isEqualTo("/api/v1/paper/tasks/101/download");
        assertThat(result.output().path("downloadFilename").asText()).isEqualTo("main-artifacts.zip");
        assertThat(result.output().path("suggestionCount").asInt()).isEqualTo(1);
        assertThat(result.output().path("selectedLiteratureCount").asInt()).isEqualTo(1);
    }

    @Test
    void cancelUsesPaperOrchestratorAndReturnsAcceptedState() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        PaperTask task = task("RUNNING", "RETRIEVE");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(artifacts.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of());

        ObjectNode args = args(TASK_ID);
        args.put("cancelReason", "user changed mind");
        ToolResult result = new PaperTaskCancelToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-3", "paper_task_cancel", args));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("cancelAccepted").asBoolean()).isTrue();
        assertThat(result.output().path("idempotent").asBoolean()).isFalse();
        verify(paperOrchestrator).stop(USER_ID, TASK_ID, "user changed mind");
    }

    @Test
    void cancelTerminalTaskIsReportedAsIdempotent() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        PaperTask task = task("COMPLETED", "COMPLETE");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(artifacts.findByTaskIdOrderByCreatedAt(TASK_ID)).thenReturn(List.of());

        ToolResult result = new PaperTaskCancelToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-4", "paper_task_cancel", args(TASK_ID)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("cancelAccepted").asBoolean()).isFalse();
        assertThat(result.output().path("idempotent").asBoolean()).isTrue();
        verify(paperOrchestrator).stop(USER_ID, TASK_ID, null);
    }

    @Test
    void missingUserContextReturnsFailure() {
        ToolResult result = new PaperPolishStatusToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-5", "paper_polish_status", args(TASK_ID)));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("缺少当前用户上下文");
    }

    @Test
    void inaccessibleTaskReturnsFailureWithoutContent() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.empty());

        ToolResult result = new PaperPolishStatusToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-6", "paper_polish_status", args(TASK_ID)));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("不可访问");
        assertThat(result.output()).isNull();
    }

    private ObjectNode args(Long taskId) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("taskId", taskId);
        return arguments;
    }

    @Test
    void waitingInputHasActionableTaskLinkWithoutClaimingCompletion() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task("WAITING_INPUT", "STRUCTURE_CHECK")));
        ToolResult result = new PaperPolishResultToolExecutor(support, objectMapper)
                .execute(new ToolCall("waiting", "paper_polish_result", args(TASK_ID)));
        assertThat(result.output().path("requiresUserInput").asBoolean()).isTrue();
        assertThat(result.output().path("completed").asBoolean()).isFalse();
        assertThat(result.output().path("pollRecommended").asBoolean()).isFalse();
        assertThat(result.output().path("taskUrl").asText()).isEqualTo("/paper?taskId=101");
    }

    @Test
    void cancellingReplayDoesNotSendAnotherStopOrClaimTerminalCancellation() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task("CANCEL_REQUESTED", "POLISH")));
        ToolResult result = new PaperTaskCancelToolExecutor(support, objectMapper)
                .execute(new ToolCall("cancel-again", "paper_task_cancel", args(TASK_ID)));
        assertThat(result.output().path("idempotent").asBoolean()).isTrue();
        assertThat(result.output().path("cancelled").asBoolean()).isFalse();
        assertThat(result.output().path("cancelling").asBoolean()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(paperOrchestrator);
    }

    @Test
    void descriptorsPermitBoundedObservationAndAuthorizedCancellation() {
        assertThat(new PaperPolishStatusToolExecutor(support, objectMapper).descriptor().repeatPolicy())
                .isEqualTo(com.yanban.core.tool.ToolDescriptor.RepeatPolicy.POLL_UNTIL_TERMINAL);
        var cancel = new PaperTaskCancelToolExecutor(support, objectMapper).descriptor();
        assertThat(cancel.requiredPermissions()).containsExactly("task:cancel");
        assertThat(cancel.confirmationPolicy()).isEqualTo(com.yanban.core.tool.ToolDescriptor.ConfirmationPolicy.NEVER);
    }

    private PaperTask task(String status, String stage) {
        PaperTask task = new PaperTask(USER_ID, "RAG Paper", "main.tex", "paper/main.tex", status, "zh", stage, null);
        task.setMode("LATEX_BIB");
        task.setLiteratureMinCount(3);
        task.setLiteratureCount(8);
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }

    private PaperTaskArtifact artifact(String type, String filename) {
        PaperTaskArtifact artifact = new PaperTaskArtifact(TASK_ID, type, "private/object-key", 1);
        artifact.setMetadataJson("{\"filename\":\"" + filename + "\"}");
        ReflectionTestUtils.setField(artifact, "id", 900L);
        return artifact;
    }
}
