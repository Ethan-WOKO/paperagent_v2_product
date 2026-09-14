package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.core.agent.AgentTaskUpsertRequest;
import com.yanban.core.user.UserAccountPolicy;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.web.PaperProcessRequest;
import com.yanban.paper.web.PaperTaskResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

class PaperTaskServiceTest {

    @Test
    void toolCreatePersistsSourcesWithoutDispatchBeforeProductBinding() {
        MockMultipartFile tex = new MockMultipartFile("mainTex", "main.tex", "application/x-tex", "latex source".getBytes());
        PaperProcessRequest request = new PaperProcessRequest(tex, null, null, null, null, null, 3, 5, false, "en", null);
        when(paperTaskRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paperStorageService.storeOriginal(eq(7L), any())).thenReturn("owned/original.tex");
        when(paperTaskRepository.save(any())).thenAnswer(call -> {
            PaperTask task = call.getArgument(0); assignId(task, 25L); return task;
        });
        PaperTaskResponse response = service.createTaskForTool(7L, request, "paper-tool.stable");
        assertThat(response.id()).isEqualTo(25L);
        assertThat(response.status()).isEqualTo("PENDING");
        verify(paperStorageService).storeOriginal(eq(7L), any());
        verify(artifactRepository).save(any());
        verify(paperOrchestrator, never()).startTask(any());
    }

    private final PaperTaskRepository paperTaskRepository = org.mockito.Mockito.mock(PaperTaskRepository.class);
    private final PaperTaskArtifactRepository artifactRepository = org.mockito.Mockito.mock(PaperTaskArtifactRepository.class);
    private final PaperStorageService paperStorageService = org.mockito.Mockito.mock(PaperStorageService.class);
    private final PaperOrchestrator paperOrchestrator = org.mockito.Mockito.mock(PaperOrchestrator.class);
    private final UserAccountPolicy userAccountPolicy = org.mockito.Mockito.mock(UserAccountPolicy.class);
    private final ObjectProvider<UserAccountPolicy> accountPolicy = provider(userAccountPolicy);
    private final com.yanban.core.agent.AgentTaskEventRecorder eventRecorder = org.mockito.Mockito.mock(com.yanban.core.agent.AgentTaskEventRecorder.class);
    private final AgentTaskRegistry agentTaskRegistry = org.mockito.Mockito.mock(AgentTaskRegistry.class);

    private final PaperTaskService service = new PaperTaskService(
            paperTaskRepository,
            artifactRepository,
            paperStorageService,
            paperOrchestrator,
            accountPolicy,
            eventRecorder,
            agentTaskRegistry
    );

    @Test
    void createTaskReusesExistingIdempotentTaskWithoutRestoringFiles() {
        MockMultipartFile tex = new MockMultipartFile("mainTex", "main.tex", "application/x-tex", "hello".getBytes());
        PaperProcessRequest request = new PaperProcessRequest(tex, null, null, null, null, null, 3, 5, false, "zh", null);
        PaperTask existing = new PaperTask(7L, "main", "main.tex", "paper/main.tex", "PENDING", "zh", "UPLOAD_RECEIVED", null, "req-1", "idem-1");
        assignId(existing, 21L);
        existing.setScoreThreshold(90);
        existing.setMaxRounds(4);
        existing.setInnerMaxAttempts(5);

        when(paperTaskRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existing));

        PaperTaskResponse response = service.createTask(7L, request, "req-1");

        assertThat(response.id()).isEqualTo(21L);
        assertThat(response.clientRequestId()).isEqualTo("req-1");
        assertThat(response.idempotent()).isTrue();
        assertThat(response.scoreThreshold()).isEqualTo(90);
        assertThat(response.maxRounds()).isEqualTo(4);
        assertThat(response.innerMaxAttempts()).isEqualTo(5);
        verify(paperStorageService, never()).storeOriginal(any(), any());
        verify(paperTaskRepository, never()).save(any());
        verify(agentTaskRegistry, never()).upsertSafely(any());
    }

    @Test
    void createTaskSyncsUnifiedTaskWithClientRequestId() {
        MockMultipartFile tex = new MockMultipartFile("mainTex", "main.tex", "application/x-tex", "\\documentclass{article}".getBytes());
        MockMultipartFile bib = new MockMultipartFile("bibFile", "refs.bib", "text/x-bibtex", "@article{a}".getBytes());
        PaperProcessRequest request = new PaperProcessRequest(tex, bib, null, null, null, null, 3, 5, false, "zh", null);

        when(paperTaskRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paperStorageService.storeOriginal(eq(7L), eq(tex))).thenReturn("paper/source.tex");
        when(paperStorageService.storeOriginal(eq(7L), eq(bib))).thenReturn("paper/source.bib");
        when(paperTaskRepository.save(any(PaperTask.class))).thenAnswer(invocation -> {
            PaperTask task = invocation.getArgument(0);
            assignId(task, 42L);
            return task;
        });

        PaperTaskResponse response = service.createTask(7L, request, "req-42");

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.clientRequestId()).isEqualTo("req-42");
        assertThat(response.idempotent()).isFalse();
        assertThat(response.scoreThreshold()).isEqualTo(80);
        assertThat(response.maxRounds()).isEqualTo(3);
        assertThat(response.innerMaxAttempts()).isEqualTo(2);

        ArgumentCaptor<AgentTaskUpsertRequest> captor = ArgumentCaptor.forClass(AgentTaskUpsertRequest.class);
        verify(agentTaskRegistry).upsertSafely(captor.capture());
        AgentTaskUpsertRequest unifiedTask = captor.getValue();
        assertThat(unifiedTask.clientRequestId()).isEqualTo("req-42");
        assertThat(unifiedTask.sourceId()).isEqualTo(42L);
        assertThat(unifiedTask.source()).isEqualTo(AgentTaskRegistry.SOURCE_PAPER_TASK);
        assertThat(unifiedTask.taskType()).isEqualTo(com.yanban.core.agent.AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH);
    }

    @Test
    void createTaskPersistsNormalizedPolishConfigAndSeparatesIdempotencyKeys() {
        MockMultipartFile tex = new MockMultipartFile("mainTex", "main.tex", "application/x-tex", "\\documentclass{article}".getBytes());
        PaperProcessRequest strict = new PaperProcessRequest(tex, null, null, 90, 4, 5, null, 5, false, "zh", null);
        PaperProcessRequest lenient = new PaperProcessRequest(tex, null, null, 70, 4, 5, null, 5, false, "zh", null);

        when(paperTaskRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paperStorageService.storeOriginal(eq(7L), eq(tex))).thenReturn("paper/source.tex");
        when(paperTaskRepository.save(any(PaperTask.class))).thenAnswer(invocation -> {
            PaperTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                assignId(task, System.nanoTime());
            }
            return task;
        });

        service.createTask(7L, strict, "same-client-request");
        service.createTask(7L, lenient, "same-client-request");

        ArgumentCaptor<String> idempotencyKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(paperTaskRepository, org.mockito.Mockito.times(2)).findByIdempotencyKey(idempotencyKeyCaptor.capture());
        assertThat(idempotencyKeyCaptor.getAllValues()).hasSize(2);
        assertThat(idempotencyKeyCaptor.getAllValues().get(0)).isNotEqualTo(idempotencyKeyCaptor.getAllValues().get(1));

        ArgumentCaptor<PaperTask> taskCaptor = ArgumentCaptor.forClass(PaperTask.class);
        verify(paperTaskRepository, org.mockito.Mockito.times(2)).save(taskCaptor.capture());
        PaperTask firstTask = taskCaptor.getAllValues().get(0);
        assertThat(firstTask.getScoreThreshold()).isEqualTo(90);
        assertThat(firstTask.getMaxRounds()).isEqualTo(4);
        assertThat(firstTask.getInnerMaxAttempts()).isEqualTo(5);
    }

    @Test
    void downloadResultExcludesSourceBibWhenMergedBibKeepsOriginalFilename() throws Exception {
        PaperTask task = new PaperTask(7L, "paper", "paper.tex", "paper/source.tex", "COMPLETED", "zh", "ASSEMBLE", null);
        assignId(task, 42L);
        PaperTaskArtifact sourceBib = artifact(42L, "source_bib", "paper/source.bib", "paper_v2_rewrite_refs.bib");
        PaperTaskArtifact mergedBib = artifact(42L, "suggested_bib", "paper/merged.bib", "paper_v2_rewrite_refs.bib");

        when(paperTaskRepository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(task));
        when(artifactRepository.findByTaskIdOrderByCreatedAt(42L)).thenReturn(List.of(sourceBib, mergedBib));
        when(paperStorageService.read("paper/merged.bib")).thenReturn("@article{merged}".getBytes(StandardCharsets.UTF_8));

        byte[] zipBytes = service.downloadResult(7L, 42L).getContentAsByteArray();
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }

        assertThat(entryNames).containsExactly("paper_v2_rewrite_refs.bib");
        verify(paperStorageService, never()).read("paper/source.bib");
    }

    private static PaperTaskArtifact artifact(Long taskId, String type, String objectKey, String filename) {
        PaperTaskArtifact artifact = new PaperTaskArtifact(taskId, type, objectKey, 1);
        artifact.setMetadataJson("{\"filename\":\"" + filename + "\"}");
        return artifact;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static void assignId(PaperTask task, Long id) {
        try {
            java.lang.reflect.Field idField = PaperTask.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(task, id);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
