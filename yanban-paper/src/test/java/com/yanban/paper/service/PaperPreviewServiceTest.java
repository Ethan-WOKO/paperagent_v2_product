package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexParserService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaperPreviewServiceTest {

    private final PaperTaskRepository tasks = mock(PaperTaskRepository.class);
    private final PaperSectionRepository sections = mock(PaperSectionRepository.class);
    private final SuggestionRepository suggestions = mock(SuggestionRepository.class);
    private final SuggestionEvidenceRepository evidence = mock(SuggestionEvidenceRepository.class);
    private final LiteratureCardRepository literatureCards = mock(LiteratureCardRepository.class);
    private final PaperTaskArtifactRepository artifacts = mock(PaperTaskArtifactRepository.class);
    private final PaperStorageService storage = mock(PaperStorageService.class);
    private final LatexParserService parser = mock(LatexParserService.class);
    private final PaperAssembleService assemble = mock(PaperAssembleService.class);

    private final PaperPreviewService service = new PaperPreviewService(
            tasks,
            sections,
            suggestions,
            evidence,
            literatureCards,
            artifacts,
            storage,
            parser,
            assemble
    );

    @Test
    void updateSectionRevisionStatusPersistsStatusAndRebuildsAdvancedArtifact() {
        PaperTask task = task();
        PaperSection section = new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80);
        assignId(section, 31L);
        when(tasks.findByIdAndUserId(7L, 11L)).thenReturn(Optional.of(task));
        when(sections.findByIdAndTaskId(31L, 7L)).thenReturn(Optional.of(section));
        when(sections.save(any(PaperSection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mockDocument(task);

        PaperSection saved = service.updateSectionRevisionStatus(11L, 7L, 31L, "accepted");

        assertThat(saved.getRevisionStatus()).isEqualTo(PaperSection.REVISION_ACCEPTED);
        verify(assemble).assemble(eq(7L), any(LatexDocument.class), eq(true));
    }

    @Test
    void updateSuggestionStatusRebuildsExistingAdvancedArtifact() {
        PaperTask task = task();
        Suggestion suggestion = new Suggestion(7L, "ADVOCACY", "RelatedWork", "Use evidence.");
        assignId(suggestion, 41L);
        PaperTaskArtifact polished = new PaperTaskArtifact(7L, "polished_tex", "paper/polished.tex", 1);
        when(tasks.findByIdAndUserId(7L, 11L)).thenReturn(Optional.of(task));
        when(suggestions.findById(41L)).thenReturn(Optional.of(suggestion));
        when(suggestions.save(any(Suggestion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.findByTaskIdOrderByCreatedAt(7L)).thenReturn(List.of(polished));
        when(evidence.findBySuggestionIdIn(List.of(41L))).thenReturn(List.of());
        when(literatureCards.findAllById(List.of())).thenReturn(List.of());
        mockDocument(task);

        service.updateSuggestionStatus(11L, 7L, 41L, "rejected");

        assertThat(suggestion.getStatus()).isEqualTo("REJECTED");
        verify(assemble).assemble(eq(7L), any(LatexDocument.class), eq(true));
    }

    @Test
    void updateSectionRevisionStatusDoesNotAssembleRunningTask() {
        PaperTask task = task("RUNNING", "POLISH");
        PaperSection section = new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80);
        assignId(section, 31L);
        when(tasks.findByIdAndUserId(7L, 11L)).thenReturn(Optional.of(task));
        when(sections.findByIdAndTaskId(31L, 7L)).thenReturn(Optional.of(section));
        when(sections.save(any(PaperSection.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperSection saved = service.updateSectionRevisionStatus(11L, 7L, 31L, "accepted");

        assertThat(saved.getRevisionStatus()).isEqualTo(PaperSection.REVISION_ACCEPTED);
        assertThat(task.getStatus()).isEqualTo("RUNNING");
        verify(assemble, never()).assemble(any(), any(), anyBoolean());
    }

    @Test
    void batchRevisionUpdateChangesSelectedSectionsAndRebuildsOnlyOnce() {
        PaperTask task = task();
        PaperSection first = new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 80);
        PaperSection second = new PaperSection(task.getId(), "main.tex", 1, 2, "Method", "METHOD", 1.0, "test", 81, 160);
        assignId(first, 31L);
        assignId(second, 32L);
        when(tasks.findByIdAndUserId(7L, 11L)).thenReturn(Optional.of(task));
        when(sections.findByTaskIdOrderByOrderIndexAsc(7L)).thenReturn(List.of(first, second));
        when(sections.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        mockDocument(task);

        List<PaperSection> saved = service.updateSectionRevisionStatuses(11L, 7L, List.of(31L, 32L), "accepted");

        assertThat(saved).extracting(PaperSection::getRevisionStatus)
                .containsOnly(PaperSection.REVISION_ACCEPTED);
        verify(assemble).assemble(eq(7L), any(LatexDocument.class), eq(true));
    }

    @Test
    void updateSuggestionStatusDoesNotAssembleWaitingInputTask() {
        PaperTask task = task("WAITING_INPUT", "CLARIFY");
        Suggestion suggestion = new Suggestion(7L, "ADVOCACY", "RelatedWork", "Use evidence.");
        assignId(suggestion, 41L);
        PaperTaskArtifact polished = new PaperTaskArtifact(7L, "polished_tex", "paper/polished.tex", 1);
        when(tasks.findByIdAndUserId(7L, 11L)).thenReturn(Optional.of(task));
        when(suggestions.findById(41L)).thenReturn(Optional.of(suggestion));
        when(suggestions.save(any(Suggestion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.findByTaskIdOrderByCreatedAt(7L)).thenReturn(List.of(polished));
        when(evidence.findBySuggestionIdIn(List.of(41L))).thenReturn(List.of());
        when(literatureCards.findAllById(List.of())).thenReturn(List.of());

        service.updateSuggestionStatus(11L, 7L, 41L, "rejected");

        assertThat(suggestion.getStatus()).isEqualTo("REJECTED");
        assertThat(task.getStatus()).isEqualTo("WAITING_INPUT");
        verify(assemble, never()).assemble(any(), any(), anyBoolean());
    }

    private void mockDocument(PaperTask task) {
        when(storage.read("paper/original.tex")).thenReturn("\\documentclass{article}\\begin{document}Hi\\end{document}".getBytes(StandardCharsets.UTF_8));
        LatexDocument document = new LatexDocument("main.tex", "Demo", List.of(), List.of(), "", "", List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
        when(parser.parse(eq("main.tex"), any(String.class), any(Map.class))).thenReturn(document);
    }

    private PaperTask task() {
        return task("COMPLETED", "COMPLETE");
    }

    private PaperTask task(String status, String stage) {
        PaperTask task = new PaperTask(11L, "Demo", "main.tex", "paper/original.tex", "COMPLETED", "en", "COMPLETE", null);
        task.setStatus(status);
        task.setCurrentStage(stage);
        task.setMainEntry("main.tex");
        assignId(task, 7L);
        return task;
    }

    private static void assignId(Object entity, Long id) {
        try {
            java.lang.reflect.Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
