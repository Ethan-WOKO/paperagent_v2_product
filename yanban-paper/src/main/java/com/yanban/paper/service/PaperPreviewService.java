package com.yanban.paper.service;

import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexParserService;
import com.yanban.paper.web.PaperSuggestionResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperPreviewService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("PROPOSED", "ACCEPTED", "REJECTED", "NEEDS_USER_DATA");
    private static final Set<String> ALLOWED_REVISION_STATUSES = Set.of(
            PaperSection.REVISION_PENDING,
            PaperSection.REVISION_ACCEPTED,
            PaperSection.REVISION_REJECTED
    );

    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final LiteratureCardRepository literatureCards;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperStorageService storageService;
    private final LatexParserService latexParserService;
    private final PaperAssembleService assembleService;
    private final ObjectProvider<PaperTaskLiteratureRepository> taskLiteratureProvider;

    public PaperPreviewService(PaperTaskRepository tasks,
                               PaperSectionRepository sections,
                               SuggestionRepository suggestions,
                               SuggestionEvidenceRepository evidenceRepository,
                               LiteratureCardRepository literatureCards,
                               PaperTaskArtifactRepository artifacts,
                               PaperStorageService storageService,
                               LatexParserService latexParserService,
                               PaperAssembleService assembleService) {
        this(tasks, sections, suggestions, evidenceRepository, literatureCards, artifacts, storageService,
                latexParserService, assembleService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperPreviewService(PaperTaskRepository tasks,
                               PaperSectionRepository sections,
                               SuggestionRepository suggestions,
                               SuggestionEvidenceRepository evidenceRepository,
                               LiteratureCardRepository literatureCards,
                               PaperTaskArtifactRepository artifacts,
                               PaperStorageService storageService,
                               LatexParserService latexParserService,
                               PaperAssembleService assembleService,
                               ObjectProvider<PaperTaskLiteratureRepository> taskLiteratureProvider) {
        this.tasks = tasks;
        this.sections = sections;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.literatureCards = literatureCards;
        this.artifacts = artifacts;
        this.storageService = storageService;
        this.latexParserService = latexParserService;
        this.assembleService = assembleService;
        this.taskLiteratureProvider = taskLiteratureProvider;
    }

    @Transactional(readOnly = true)
    public List<PaperSuggestionResponse> listSuggestions(Long userId, Long taskId) {
        assertOwned(userId, taskId);
        List<Suggestion> taskSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        Map<Long, List<LiteratureCard>> evidenceCards = evidenceCards(taskSuggestions);
        Map<Long, PaperTaskLiterature> literatureByCardId = literatureByCardId(taskId);
        return taskSuggestions.stream()
                .map(suggestion -> PaperSuggestionResponse.from(suggestion, evidenceCards.getOrDefault(suggestion.getId(), List.of()), literatureByCardId))
                .toList();
    }

    private Map<Long, PaperTaskLiterature> literatureByCardId(Long taskId) {
        if (taskLiteratureProvider == null || taskLiteratureProvider.getIfAvailable() == null) return Map.of();
        return taskLiteratureProvider.getIfAvailable().findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .collect(Collectors.toMap(PaperTaskLiterature::getCardId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    @Transactional
    public PaperSuggestionResponse updateSuggestionStatus(Long userId, Long taskId, Long suggestionId, String status) {
        PaperTask task = assertOwned(userId, taskId);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported suggestion status");
        }
        Suggestion suggestion = suggestions.findById(suggestionId)
                .filter(item -> item.getTaskId().equals(taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "suggestion not found"));
        suggestion.setStatus(normalized);
        Suggestion saved = suggestions.save(suggestion);
        refreshArtifacts(task, hasCompletedArtifact(taskId, "polished_tex"));
        Map<Long, List<LiteratureCard>> evidenceCards = evidenceCards(List.of(saved));
        return PaperSuggestionResponse.from(saved, evidenceCards.getOrDefault(saved.getId(), List.of()));
    }

    @Transactional
    public PaperSection updateSectionRevisionStatus(Long userId, Long taskId, Long sectionId, String status) {
        PaperTask task = assertOwned(userId, taskId);
        String normalized = normalizeRevisionStatus(status);
        PaperSection section = sections.findByIdAndTaskId(sectionId, taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper section not found"));
        section.setRevisionStatus(normalized);
        PaperSection saved = sections.save(section);
        refreshArtifacts(task, true);
        return saved;
    }

    @Transactional
    public List<PaperSection> updateSectionRevisionStatuses(Long userId,
                                                            Long taskId,
                                                            List<Long> sectionIds,
                                                            String status) {
        PaperTask task = assertOwned(userId, taskId);
        String normalized = normalizeRevisionStatus(status);
        Set<Long> requestedIds = sectionIds == null ? Set.of() : sectionIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "section ids are required");
        }
        List<PaperSection> taskSections = sections.findByTaskIdOrderByOrderIndexAsc(taskId);
        Map<Long, PaperSection> byId = taskSections.stream()
                .filter(section -> section.getId() != null)
                .collect(Collectors.toMap(PaperSection::getId, section -> section));
        if (!byId.keySet().containsAll(requestedIds)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "one or more paper sections were not found");
        }
        List<PaperSection> changed = requestedIds.stream().map(byId::get).toList();
        changed.forEach(section -> section.setRevisionStatus(normalized));
        List<PaperSection> saved = sections.saveAll(changed);
        refreshArtifacts(task, true);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PaperTaskArtifact> listArtifacts(Long userId, Long taskId) {
        assertOwned(userId, taskId);
        return artifacts.findByTaskIdOrderByCreatedAt(taskId);
    }

    private String normalizeRevisionStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_REVISION_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported section revision status");
        }
        return normalized;
    }

    private boolean hasCompletedArtifact(Long taskId, String type) {
        return artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .anyMatch(artifact -> type.equals(artifact.getType())
                        && PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()));
    }

    private void refreshArtifacts(PaperTask task, boolean advancedMode) {
        if (!"COMPLETED".equalsIgnoreCase(task.getStatus())) {
            return;
        }
        if (task.getObjectKey() == null || task.getObjectKey().isBlank()) {
            return;
        }
        String tex = new String(storageService.read(task.getObjectKey()), StandardCharsets.UTF_8);
        LatexDocument document = latexParserService.parse(task.getMainEntry(), tex, sourceBibFiles(task.getId()));
        assembleService.assemble(task.getId(), document, advancedMode);
    }

    private Map<String, String> sourceBibFiles(Long taskId) {
        Map<String, String> bibFiles = new LinkedHashMap<>();
        artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> "source_bib".equals(artifact.getType()))
                .forEach(artifact -> bibFiles.put("refs.bib", new String(storageService.read(artifact.getObjectKey()), StandardCharsets.UTF_8)));
        return bibFiles;
    }

    private Map<Long, List<LiteratureCard>> evidenceCards(List<Suggestion> taskSuggestions) {
        if (taskSuggestions == null || taskSuggestions.isEmpty()) return Map.of();
        List<Long> suggestionIds = taskSuggestions.stream().map(Suggestion::getId).toList();
        List<SuggestionEvidence> evidences = evidenceRepository.findBySuggestionIdIn(suggestionIds);
        Set<Long> cardIds = evidences.stream()
                .map(SuggestionEvidence::getCardId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LiteratureCard> cardById = literatureCards.findAllById(cardIds).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, card -> card));
        Map<Long, List<LiteratureCard>> result = new LinkedHashMap<>();
        for (SuggestionEvidence evidence : evidences) {
            LiteratureCard card = cardById.get(evidence.getCardId());
            if (card != null) {
                result.computeIfAbsent(evidence.getSuggestionId(), ignored -> new ArrayList<>()).add(card);
            }
        }
        return result;
    }

    private PaperTask assertOwned(Long userId, Long taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
    }
}
