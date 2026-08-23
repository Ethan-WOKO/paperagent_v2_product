package com.yanban.paper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
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
import com.yanban.paper.latex.LatexBibEntry;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexSection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

@Service
public class PaperAssembleService {

    private static final Pattern BIB_KEY_PATTERN = Pattern.compile("\\\\(?:cite|citep|citet|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}");
    private static final Pattern LABEL_PATTERN = Pattern.compile("\\\\label\\s*\\{([^{}]+)}");
    private static final Pattern REF_PATTERN = Pattern.compile("\\\\(?:ref|eqref|cref|Cref|autoref|pageref)\\*?\\s*\\{([^{}]+)}");
    private static final Pattern CITATION_SLOT_PATTERN = Pattern.compile("\\\\yanbancitationslot\\s*\\{\\d+}");
    private static final Pattern COMMENT_LINE_PATTERN = Pattern.compile("(?m)^\\s*%.*$");
    private static final double SUPPLEMENTAL_BIB_MIN_SCORE = 0.0;

    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final LiteratureCardRepository literatureCards;
    private final PaperStorageService storageService;
    private final LatexMaskingService maskingService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PaperCitationApplyService> citationApplyServiceProvider;
    private final ObjectProvider<PaperFinalAuditService> finalAuditServiceProvider;

    public PaperAssembleService(PaperTaskRepository tasks,
                                PaperSectionRepository sections,
                                PaperTaskArtifactRepository artifacts,
                                PaperTaskAnalysisRepository analyses,
                                PaperTaskLiteratureRepository taskLiterature,
                                SuggestionRepository suggestions,
                                SuggestionEvidenceRepository evidenceRepository,
                                LiteratureCardRepository literatureCards,
                                PaperStorageService storageService,
                                 LatexMaskingService maskingService,
                                 ObjectMapper objectMapper,
                                 ObjectProvider<PaperCitationApplyService> citationApplyServiceProvider,
                                 ObjectProvider<PaperFinalAuditService> finalAuditServiceProvider) {
        this.tasks = tasks;
        this.sections = sections;
        this.artifacts = artifacts;
        this.analyses = analyses;
        this.taskLiterature = taskLiterature;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.literatureCards = literatureCards;
        this.storageService = storageService;
        this.maskingService = maskingService;
        this.objectMapper = objectMapper;
        this.citationApplyServiceProvider = citationApplyServiceProvider;
        this.finalAuditServiceProvider = finalAuditServiceProvider;
    }

    @Transactional
    public PaperAssembleResult assemble(Long taskId, LatexDocument document, boolean advancedMode) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        List<PaperSection> storedSections = sections.findByTaskIdOrderByOrderIndexAsc(taskId);
        Map<Integer, PaperSection> sectionByOrder = storedSections.stream()
                .collect(Collectors.toMap(PaperSection::getOrderIndex, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, String> polishedTextBySectionId = readPolishedSectionTexts(storedSections);
        String assembledTex = advancedMode ? assembleTex(document, sectionByOrder, polishedTextBySectionId) : "";
        List<Suggestion> taskSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        Map<Long, List<LiteratureCard>> evidenceCards = evidenceCards(taskSuggestions);
        int literatureLimit = literatureLimit(task);
        String originalBib = sourceBibContent(taskId);
        String bibFilename = sourceBibFilename(taskId);
        PaperCitationApplyService citationApplyService = citationApplyServiceProvider == null
                ? null : citationApplyServiceProvider.getIfAvailable();
        PaperCitationApplyService.CitationApplyResult citationResult = citationApplyService == null
                ? legacyCitationResult(taskId, taskSuggestions, evidenceCards, document, literatureLimit, assembledTex, bibFilename)
                : citationApplyService.apply(assembledTex, originalBib, bibFilename, document.bibliography(), taskSuggestions, evidenceCards);
        String polishedTex = advancedMode ? citationResult.polishedTex() : "";
        BibBuildResult bibResult = toBibBuildResult(citationResult);
        String suggestedBib = bibResult.allBib();
        PaperFinalAuditService finalAuditService = finalAuditServiceProvider == null
                ? null : finalAuditServiceProvider.getIfAvailable();
        PaperFinalAuditService.AuditResult finalAudit = advancedMode && finalAuditService != null
                ? finalAuditService.audit(polishedTex, suggestedBib, citationResult, sourceResourceNames(taskId))
                : PaperFinalAuditService.AuditResult.notRun();
        String reviewReport = buildReviewReport(task, storedSections, taskSuggestions, evidenceCards, advancedMode, bibResult, finalAudit);
        Set<String> mergedKeys = new LinkedHashSet<>(document.bibliography().keySet());
        mergedKeys.addAll(citationResult.newBibKeys());
        Set<String> lintCodeSet = new LinkedHashSet<>();
        if (advancedMode) {
            validateAssembledTex(polishedTex, mergedKeys).stream().map(LatexLintIssue::code).forEach(lintCodeSet::add);
            finalAudit.issues().stream().map(PaperFinalAuditService.AuditIssue::code).forEach(lintCodeSet::add);
        }
        List<String> lintCodes = List.copyOf(lintCodeSet);

        List<Map<String, Object>> artifactResults = new ArrayList<>();
        if (advancedMode) {
            artifactResults.add(saveArtifact(task, "polished_tex", "polished.tex", polishedTex, "application/x-tex; charset=UTF-8", Map.of("advancedMode", true, "lintCodes", lintCodes, "finalAuditStatus", finalAudit.status())));
        }
        artifactResults.add(saveArtifact(task, "suggested_bib", citationResult.bibFilename(), suggestedBib, "text/x-bibtex; charset=UTF-8", Map.of(
                "source", "uploaded-bib-plus-accepted-citations",
                "dedup", "doi-or-title",
                "citationRecommendations", citationResult.appliedPatches().size(),
                "newReferences", citationResult.newReferenceCount(),
                "reusedReferences", citationResult.reusedExistingReferenceCount(),
                "citationLocationsUpdated", citationResult.changedCitationLocationCount(),
                "existingCitationLocationsVerified", citationResult.verifiedExistingCitationLocationCount(),
                "unappliedRecommendations", citationResult.manualPatches().size())));
        artifactResults.add(saveArtifact(task, "suggested_bib_novel", "suggested-novel.bib", bibResult.novelBib(), "text/x-bibtex; charset=UTF-8", Map.of(
                "source", "real-literature-cards", "dedup", "uploaded-bib", "newReferences", citationResult.newReferenceCount())));
        artifactResults.add(saveArtifact(task, "review_report", "review-report.md", reviewReport, "text/markdown; charset=UTF-8", Map.of("advancedMode", advancedMode, "suggestionCount", taskSuggestions.size(), "finalAuditStatus", finalAudit.status())));
        if (advancedMode && !artifactResults.isEmpty()) {
            task.setFinalObjectKey(String.valueOf(artifactResults.get(0).get("objectKey")));
        }
        task.setCurrentStage("ASSEMBLE");
        task.setStatus("COMPLETED");
        tasks.save(task);
        return new PaperAssembleResult(taskId, advancedMode, polishedTex, suggestedBib, reviewReport, artifactResults, lintCodes);
    }

    public String buildDraft(LatexDocument document, List<PaperSection> storedSections) {
        List<PaperSection> availableSections = storedSections == null ? List.of() : storedSections;
        Map<Integer, PaperSection> sectionByOrder = availableSections.stream()
                .collect(Collectors.toMap(PaperSection::getOrderIndex, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        return assembleTex(document, sectionByOrder, readPolishedSectionTexts(availableSections));
    }

    public String buildCitationAppliedDraft(Long taskId, LatexDocument document, List<PaperSection> storedSections) {
        String draft = buildDraft(document, storedSections);
        PaperCitationApplyService citationApplyService = citationApplyServiceProvider == null
                ? null : citationApplyServiceProvider.getIfAvailable();
        if (citationApplyService == null) return draft;
        List<Suggestion> taskSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        return citationApplyService.apply(
                draft,
                sourceBibContent(taskId),
                sourceBibFilename(taskId),
                document.bibliography(),
                taskSuggestions,
                evidenceCards(taskSuggestions)).polishedTex();
    }

    private BibBuildResult toBibBuildResult(PaperCitationApplyService.CitationApplyResult result) {
        return new BibBuildResult(result.mergedBib(), result.novelBib(), result.novelCards(),
                versionReuseSummaries(result.bibliography()),
                result.existingBibliographyCount(), result.appliedPatches(), result.manualPatches(), result.bibFilename(),
                result.newReferenceCount(), result.reusedExistingReferenceCount(), result.changedCitationLocationCount(),
                result.verifiedExistingCitationLocationCount());
    }

    private List<Map<String, Object>> versionReuseSummaries(List<Map<String, Object>> bibliography) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> item : bibliography == null ? List.<Map<String, Object>>of() : bibliography) {
            if (!"POSSIBLE_CONFERENCE_JOURNAL_VERSION".equals(item.get("reuseReason"))) continue;
            byKey.putIfAbsent(String.valueOf(item.get("bibKey")), new LinkedHashMap<>(item));
        }
        return List.copyOf(byKey.values());
    }

    private PaperCitationApplyService.CitationApplyResult legacyCitationResult(Long taskId,
                                                                                List<Suggestion> taskSuggestions,
                                                                                Map<Long, List<LiteratureCard>> evidenceCards,
                                                                                LatexDocument document,
                                                                                int literatureLimit,
                                                                                String assembledTex,
                                                                                String bibFilename) {
        BibBuildResult legacy = buildSuggestedBib(taskId, taskSuggestions, evidenceCards, document.bibliography(), literatureLimit);
        return new PaperCitationApplyService.CitationApplyResult(
                assembledTex, legacy.allBib(), legacy.novelBib(), legacy.novelCards(), List.of(),
                List.of(), List.of(), legacy.existingBibliographyCount(), Set.of(), bibFilename);
    }

    private String sourceBibContent(Long taskId) {
        return artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> "source_bib".equals(artifact.getType()))
                .filter(artifact -> PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()))
                .reduce((left, right) -> right)
                .map(artifact -> new String(storageService.read(artifact.getObjectKey()), StandardCharsets.UTF_8))
                .orElse("");
    }

    private Set<String> sourceResourceNames(Long taskId) {
        Set<String> names = new LinkedHashSet<>();
        artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> artifact.getType() != null && artifact.getType().startsWith("source_"))
                .forEach(artifact -> {
                    try {
                        String filename = objectMapper.readTree(artifact.getMetadataJson()).path("filename").asText("").trim();
                        if (!filename.isBlank()) names.add(filename);
                    } catch (Exception ignored) {
                        // Old source artifacts may not carry filename metadata.
                    }
                });
        return names;
    }

    private String sourceBibFilename(Long taskId) {
        return artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> "source_bib".equals(artifact.getType()))
                .filter(artifact -> PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()))
                .reduce((left, right) -> right)
                .map(this::artifactFilename)
                .orElse("references.bib");
    }

    private String artifactFilename(PaperTaskArtifact artifact) {
        String metadata = artifact.getMetadataJson();
        String marker = "\"filename\":\"";
        if (metadata != null) {
            int start = metadata.indexOf(marker);
            if (start >= 0) {
                start += marker.length();
                int end = metadata.indexOf('"', start);
                if (end > start) {
                    String filename = metadata.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
                    if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".bib") && !filename.contains("/") && !filename.contains("\\")) return filename;
                }
            }
        }
        return "references.bib";
    }

    private Map<Long, String> readPolishedSectionTexts(List<PaperSection> storedSections) {
        Map<Long, String> texts = new LinkedHashMap<>();
        for (PaperSection section : storedSections) {
            if (!"POLISHED".equalsIgnoreCase(section.getPolishStatus())) {
                continue;
            }
            if (!PaperSection.REVISION_ACCEPTED.equalsIgnoreCase(section.getRevisionStatus())) {
                continue;
            }
            String objectKey = section.getPolishedObjectKey();
            if (objectKey != null && !objectKey.isBlank() && !objectKey.startsWith("memory://")) {
                try {
                    texts.put(section.getId(), new String(storageService.read(objectKey), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // v1 polish service may not have persisted section body yet; fall back to raw LatexSection.
                }
            }
        }
        return texts;
    }

    private String assembleTex(LatexDocument document, Map<Integer, PaperSection> sectionByOrder, Map<Long, String> polishedTextBySectionId) {
        List<LatexSection> docSections = document.sections().stream()
                .sorted(Comparator.comparingInt(LatexSection::orderIndex))
                .toList();
        if (docSections.isEmpty()) {
            return document.preamble();
        }
        StringBuilder builder = new StringBuilder();
        if (document.preamble() != null && !document.preamble().isBlank()) {
            builder.append(document.preamble());
            if (!document.preamble().endsWith("\n")) builder.append('\n');
            builder.append("\\begin{document}\n");
            if (document.frontMatter() != null && !document.frontMatter().isBlank()) {
                builder.append(stripDocumentEnd(document.frontMatter()));
                if (!document.frontMatter().endsWith("\n")) builder.append('\n');
            }
        }
        for (LatexSection section : docSections) {
            PaperSection stored = sectionByOrder.get(section.orderIndex());
            String text = stored == null ? null : polishedTextBySectionId.get(stored.getId());
            if (text == null || text.isBlank()) {
                text = section.rawText();
            }
            text = stripDocumentEnd(text);
            builder.append(text);
            if (!text.endsWith("\n")) builder.append('\n');
        }
        if (document.preamble() != null && !document.preamble().isBlank()) {
            builder.append("\\end{document}\n");
        }
        return builder.toString();
    }

    private String stripDocumentEnd(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("(?is)\\\\end\\s*\\{document}\\s*$", "");
    }

    private Map<Long, List<LiteratureCard>> evidenceCards(List<Suggestion> taskSuggestions) {
        List<Long> suggestionIds = taskSuggestions.stream().map(Suggestion::getId).filter(Objects::nonNull).toList();
        if (suggestionIds.isEmpty()) return Map.of();
        List<SuggestionEvidence> evidences = evidenceRepository.findBySuggestionIdIn(suggestionIds);
        Set<Long> cardIds = evidences.stream().map(SuggestionEvidence::getCardId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LiteratureCard> cardsById = literatureCards.findAllById(cardIds).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity()));
        Map<Long, List<LiteratureCard>> result = new LinkedHashMap<>();
        for (SuggestionEvidence evidence : evidences) {
            LiteratureCard card = cardsById.get(evidence.getCardId());
            if (card != null) {
                result.computeIfAbsent(evidence.getSuggestionId(), ignored -> new ArrayList<>()).add(card);
            }
        }
        return result;
    }

    private BibBuildResult buildSuggestedBib(Long taskId, List<Suggestion> taskSuggestions, Map<Long, List<LiteratureCard>> evidenceCards, Map<String, LatexBibEntry> existingBibliography, int literatureLimit) {
        Set<Long> usedCards = new LinkedHashSet<>();
        Set<Long> blockedCards = new LinkedHashSet<>();
        for (Suggestion suggestion : taskSuggestions) {
            List<LiteratureCard> cards = evidenceCards.getOrDefault(suggestion.getId(), List.of());
            if ("ACCEPTED".equalsIgnoreCase(suggestion.getStatus())
                    && ("ADVOCACY".equalsIgnoreCase(suggestion.getTrack()) || Boolean.TRUE.equals(suggestion.getApplicable()))) {
                cards.forEach(card -> usedCards.add(card.getId()));
            } else {
                cards.forEach(card -> blockedCards.add(card.getId()));
            }
        }
        blockedCards.removeAll(usedCards);
        List<PaperTaskLiterature> selectedLiterature = taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .filter(item -> item.getRelevanceScore() >= SUPPLEMENTAL_BIB_MIN_SCORE)
                .filter(item -> item.getCardId() != null && !usedCards.contains(item.getCardId()) && !blockedCards.contains(item.getCardId()))
                .toList();
        Set<String> usedCategories = new LinkedHashSet<>();
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        for (PaperTaskLiterature item : selectedLiterature) {
            if (usedCards.size() >= literatureLimit) break;
            String category = normalizedLiteratureCategory(item);
            if (usedCategories.add(category)) {
                usedCards.add(item.getCardId());
                perCategory.put(category, perCategory.getOrDefault(category, 0) + 1);
            }
        }
        for (PaperTaskLiterature item : selectedLiterature) {
            if (usedCards.size() >= literatureLimit) break;
            if (usedCards.contains(item.getCardId())) continue;
            String category = normalizedLiteratureCategory(item);
            int count = perCategory.getOrDefault(category, 0);
            if (count >= 2) continue;
            usedCards.add(item.getCardId());
            perCategory.put(category, count + 1);
        }
        for (PaperTaskLiterature item : selectedLiterature) {
            if (usedCards.size() >= literatureLimit) break;
            if (usedCards.contains(item.getCardId())) continue;
            usedCards.add(item.getCardId());
        }
        StringBuilder bib = new StringBuilder();
        StringBuilder novelBib = new StringBuilder();
        Set<String> usedKeys = new LinkedHashSet<>(existingBibliography == null ? Set.of() : existingBibliography.keySet());
        Set<String> existingTitles = existingTitleFingerprints(existingBibliography);
        Set<String> existingDois = existingDois(existingBibliography);
        List<Map<String, Object>> duplicateCards = new ArrayList<>();
        List<Map<String, Object>> novelCards = new ArrayList<>();
        int written = 0;
        for (Long cardId : usedCards) {
            if (written >= literatureLimit) break;
            Optional<LiteratureCard> cardOpt = literatureCards.findById(cardId);
            if (cardOpt.isEmpty()) continue;
            LiteratureCard card = cardOpt.get();
            String key = uniqueBibKey(card, usedKeys);
            usedKeys.add(key);
            written++;
            String entry = bibEntry(key, card);
            bib.append(entry);
            if (alreadyInBibliography(card, existingTitles, existingDois)) {
                duplicateCards.add(cardSummary(card));
            } else {
                novelBib.append(entry);
                novelCards.add(cardSummary(card));
            }
        }
        return new BibBuildResult(bib.toString(), novelBib.toString(), novelCards, duplicateCards, existingBibliography == null ? 0 : existingBibliography.size());
    }

    private String bibEntry(String key, LiteratureCard card) {
        StringBuilder bib = new StringBuilder();
        bib.append("@article{").append(key).append(",\n")
                .append("  title={").append(escapeBib(card.getTitle())).append("},\n")
                .append("  author={").append(escapeBib(authors(card))).append("},\n");
        if (card.getPublicationYear() != null) bib.append("  year={").append(card.getPublicationYear()).append("},\n");
        if (notBlank(card.getVenue())) bib.append("  journal={").append(escapeBib(card.getVenue())).append("},\n");
        if (notBlank(card.getDoi())) bib.append("  doi={").append(escapeBib(card.getDoi())).append("},\n");
        if (notBlank(card.getUrl())) bib.append("  url={").append(escapeBib(card.getUrl())).append("},\n");
        bib.append("}\n\n");
        return bib.toString();
    }

    private boolean alreadyInBibliography(LiteratureCard card, Set<String> existingTitles, Set<String> existingDois) {
        String doi = normalizeDoi(card.getDoi());
        if (notBlank(doi) && existingDois.contains(doi)) return true;
        String title = titleFingerprint(card.getTitle());
        return notBlank(title) && existingTitles.contains(title);
    }

    private Set<String> existingTitleFingerprints(Map<String, LatexBibEntry> bibliography) {
        if (bibliography == null || bibliography.isEmpty()) return Set.of();
        return bibliography.values().stream()
                .map(entry -> entry.fields() == null ? "" : titleFingerprint(entry.fields().get("title")))
                .filter(this::notBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> existingDois(Map<String, LatexBibEntry> bibliography) {
        if (bibliography == null || bibliography.isEmpty()) return Set.of();
        return bibliography.values().stream()
                .map(entry -> entry.fields() == null ? "" : normalizeDoi(entry.fields().get("doi")))
                .filter(this::notBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String titleFingerprint(String title) {
        if (title == null) return "";
        return title.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[{}]", "")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeDoi(String doi) {
        if (doi == null) return "";
        return doi.toLowerCase(java.util.Locale.ROOT)
                .replace("https://doi.org/", "")
                .replace("http://doi.org/", "")
                .trim();
    }

    private Map<String, Object> cardSummary(LiteratureCard card) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cardId", card.getId());
        map.put("title", card.getTitle());
        map.put("year", card.getPublicationYear());
        map.put("doi", card.getDoi());
        return map;
    }

    private record BibBuildResult(String allBib,
                                  String novelBib,
                                  List<Map<String, Object>> novelCards,
                                  List<Map<String, Object>> duplicateCards,
                                  int existingBibliographyCount,
                                  List<Map<String, Object>> appliedPatches,
                                  List<Map<String, Object>> manualPatches,
                                  String bibFilename,
                                  int newReferenceCount,
                                  int reusedReferenceCount,
                                  int changedCitationLocationCount,
                                  int verifiedExistingCitationLocationCount) {
        private BibBuildResult(String allBib, String novelBib, List<Map<String, Object>> novelCards,
                                List<Map<String, Object>> duplicateCards, int existingBibliographyCount) {
            this(allBib, novelBib, novelCards, duplicateCards, existingBibliographyCount, List.of(), List.of(),
                    "references.bib", novelCards.size(), duplicateCards.size(), 0, 0);
        }
    }

    private int literatureLimit(PaperTask task) {
        Integer value = task.getLiteratureCount();
        return value == null ? 20 : Math.max(1, Math.min(100, value));
    }

    private String normalizedLiteratureCategory(PaperTaskLiterature item) {
        String text = ((item.getNarrativeRole() == null ? "" : item.getNarrativeRole()) + " "
                + (item.getSourceQuery() == null ? "" : item.getSourceQuery())).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("learning") || text.contains("deep")) return "learning-waveform";
        if (text.contains("semidefinite") || text.contains("sdp") || text.contains("manifold") || text.contains("majorization")) return "optimization-methods";
        if (text.contains("constant") || text.contains("unimodular") || text.contains("sidelobe") || text.contains("modulus")) return "constant-modulus-waveform";
        if (text.contains("polarimetric fda") || (text.contains("polarimetric") && text.contains("fda"))) return "polarimetric-fda-mimo";
        if (text.contains("polar") || text.contains("polarization")) return "polarimetric-anti-jamming";
        if (text.contains("jamming") || text.contains("interference")) return "anti-jamming";
        if (text.contains("waveform")) return "waveform-diversity";
        return text.isBlank() ? "general" : text.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private String buildReviewReport(PaperTask task, List<PaperSection> sections, List<Suggestion> taskSuggestions,
                                     Map<Long, List<LiteratureCard>> evidenceCards, boolean advancedMode, BibBuildResult bibResult,
                                     PaperFinalAuditService.AuditResult finalAudit) {
        StringBuilder report = new StringBuilder();
        report.append("# Paper Review Report\n\n")
                .append("- Task: ").append(task.getTitle()).append("\n")
                .append("- Mode: ").append(advancedMode ? "advanced" : "basic").append("\n\n")
                .append("## Section Polish Summary\n\n");
        for (PaperSection section : sections) {
            report.append("- ").append(section.getTitle()).append(" (`").append(section.getRole()).append("`): ")
                    .append(section.getPolishStatus() == null ? "NOT_POLISHED" : section.getPolishStatus());
            String reasonCode = sectionReasonCode(section);
            if (!reasonCode.isBlank()) {
                report.append(" — ").append(reasonCode);
            }
            report.append("\n");
        }
        appendSectionFailureDistribution(report, sections);
        report.append("\n## Introduction Analysis Diagnostics\n\n");
        appendIntroductionAnalysisDiagnostics(report, task.getId());
        report.append("\n## Introduction Citation Slots\n\n");
        appendCitationSlots(report, task.getId());
        report.append("\n## Retrieval Diagnostics\n\n");
        appendRetrievalDiagnostics(report, task.getId());
        appendSupplementalBibliography(report, task.getId(), evidenceCards, literatureLimit(task));
        appendUploadedBibDedupSummary(report, bibResult);
        report.append("\n## Citation Application\n\n")
                .append("- Merged bibliography filename: `").append(bibResult.bibFilename()).append("`\n")
                .append("- New recommended references added: ").append(bibResult.newReferenceCount()).append("\n")
                .append("- Existing uploaded references reused: ").append(bibResult.reusedReferenceCount()).append("\n")
                .append("- Citation recommendations accepted: ").append(bibResult.appliedPatches().size()).append("\n")
                .append("- Citation locations updated: ").append(bibResult.changedCitationLocationCount()).append("\n")
                .append("- Existing citation locations verified: ").append(bibResult.verifiedExistingCitationLocationCount()).append("\n")
                .append("- Approved recommendations that could not be applied: ").append(bibResult.manualPatches().size()).append("\n");
        Map<Long, Suggestion> suggestionById = taskSuggestions.stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(Suggestion::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        for (Map<String, Object> patch : bibResult.appliedPatches()) {
            Suggestion suggestion = suggestionById.get(longValue(patch.get("suggestionId")));
            report.append("- ").append(Boolean.TRUE.equals(patch.get("citationChanged"))
                            ? "Added citations for: " : "Confirmed existing citations for: ")
                    .append(customerText(suggestion == null ? "Citation recommendation" : suggestion.getStatement()))
                    .append(" (keys ").append(patch.get("bibKeys")).append(")\n");
        }
        for (Map<String, Object> patch : bibResult.manualPatches()) {
            Suggestion suggestion = suggestionById.get(longValue(patch.get("suggestionId")));
            report.append("- Not applied: ")
                    .append(customerText(suggestion == null ? "Citation recommendation" : suggestion.getStatement()))
                    .append(" - ").append(customerText(String.valueOf(patch.get("message")))).append("\n");
        }
        report.append('\n');
        appendCitationClosure(report, task.getId(), bibResult);
        appendFinalArtifactAudit(report, finalAudit);
        appendGlobalReview(report, task.getId());
        report.append("\n## Suggestions\n\n");
        if (taskSuggestions.isEmpty()) {
            report.append("No suggestions generated. Check Retrieval Diagnostics above to distinguish empty query, source failure, or zero selected candidates.\n\n");
        }
        for (Suggestion suggestion : taskSuggestions) {
            report.append("### ").append(suggestion.getCategory()).append("\n")
                    .append("- Purpose: ").append("ADVOCACY".equalsIgnoreCase(suggestion.getTrack())
                            ? "Citation recommendation" : "Review note").append("\n")
                    .append("- Severity: ").append(suggestion.getSeverity()).append("\n")
                    .append("- Status: ").append(suggestion.getStatus()).append("\n")
                    .append("- Applicable: ").append(suggestion.getApplicable()).append("\n")
                    .append("- Recommendation: ").append(customerText(suggestion.getStatement())).append("\n")
                    .append("- Evidence:\n");
            List<LiteratureCard> cards = evidenceCards.getOrDefault(suggestion.getId(), List.of());
            if (cards.isEmpty()) {
                report.append("  - None (not grounded; do not patch into paper)\n");
            } else {
                for (LiteratureCard card : cards) {
                    report.append("  - ").append(card.getTitle())
                            .append(card.getPublicationYear() == null ? "" : " (" + card.getPublicationYear() + ")")
                            .append("\n");
                }
            }
            appendSuggestionDecision(report, suggestion);
            report.append('\n');
        }
        report.append("## Disclaimer\n\n")
                .append("This report is an AI-assisted self-check and editing aid. It is not a substitute for peer review, advisor feedback, or venue-specific submission checks. Verify every citation, claim, and LaTeX change before submission.\n");
        return report.toString();
    }

    private void appendCitationClosure(StringBuilder report, Long taskId, BibBuildResult bibResult) {
        report.append("## Citation Review and Application\n\n");
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getGapMatrixJson() == null || analysis.getGapMatrixJson().isBlank()) {
            report.append("- Status: NOT_RUN\n- No evidence-review diagnostics recorded.\n\n");
            return;
        }
        try {
            Map<String, Object> matrix = objectMapper.readValue(
                    analysis.getGapMatrixJson(), new TypeReference<Map<String, Object>>() {});
            Object critic = matrix.get("citationCritic");
            Object closureLoop = matrix.get("citationClosureLoop");
            Map<?, ?> criticMap = critic instanceof Map<?, ?> value ? value : Map.of();
            Map<?, ?> loopMap = closureLoop instanceof Map<?, ?> value ? value : Map.of();
            if (criticMap.isEmpty() && loopMap.isEmpty()) {
                report.append("- Status: NOT_RUN\n- No evidence-review diagnostics recorded.\n\n");
                return;
            }
            String criticStatus = String.valueOf(valueOrDefault(criticMap, "closureStatus", "NOT_RUN"));
            boolean hasWithheldCandidates = !"0".equals(String.valueOf(valueOrDefault(criticMap, "withheldCount", 0)));
            String loopStatus = String.valueOf(valueOrDefault(loopMap, "status", "NOT_RUN"));
            boolean loopCompleted = "COMPLETED".equals(loopStatus);
            boolean loopHasReportOnly = !"0".equals(String.valueOf(valueOrDefault(loopMap, "reportOnlyCount", 0)));
            String closureStatus = loopCompleted
                    ? (loopHasReportOnly || !bibResult.manualPatches().isEmpty() ? "PARTIAL" : "PASS")
                    : ("DEGRADED".equals(criticStatus)
                    ? "DEGRADED"
                    : ("PARTIAL".equals(criticStatus) || hasWithheldCandidates || !bibResult.manualPatches().isEmpty()
                    ? "PARTIAL" : criticStatus));
            report.append("- Status: ").append(closureStatus).append("\n")
                    .append("- Initial evidence-review status: ").append(criticStatus).append("\n")
                    .append("- Recommendations reviewed: ").append(valueOrDefault(criticMap, "candidateCount", 0)).append("\n")
                    .append("- Supported recommendations: ").append(valueOrDefault(criticMap, "supportedCount", 0)).append("\n")
                    .append("- Recommendations held for focused review: ").append(valueOrDefault(criticMap, "withheldCount", 0)).append("\n")
                    .append("- Review batches: ").append(valueOrDefault(criticMap, "batchCount", 0)).append("\n")
                    .append("- Successful review batches: ").append(valueOrDefault(criticMap, "successfulBatchCount", 0)).append("\n")
                    .append("- Failed review batches: ").append(valueOrDefault(criticMap, "failedBatchCount", 0)).append("\n")
                    .append("- Retried review batches: ").append(valueOrDefault(criticMap, "retriedBatchCount", 0)).append("\n")
                    .append("- Citation recommendations accepted: ").append(bibResult.appliedPatches().size()).append("\n")
                    .append("- New recommended references: ").append(bibResult.newReferenceCount()).append("\n")
                    .append("- Existing references reused: ").append(bibResult.reusedReferenceCount()).append("\n")
                    .append("- Citation locations updated: ").append(bibResult.changedCitationLocationCount()).append("\n")
                    .append("- Existing citation locations verified: ").append(bibResult.verifiedExistingCitationLocationCount()).append("\n")
                    .append("- Approved recommendations that could not be applied: ").append(bibResult.manualPatches().size()).append("\n");
            if (!loopMap.isEmpty()) {
                report.append("- Focused citation review status: ").append(loopStatus).append("\n")
                        .append("- Recommendations sent for focused review: ").append(valueOrDefault(loopMap, "eligibleCount", 0)).append("\n")
                        .append("- Revised and accepted: ").append(valueOrDefault(loopMap, "acceptedCount", 0)).append("\n")
                        .append("- Recommendations not approved after evidence review: ").append(valueOrDefault(loopMap, "reportOnlyCount", 0)).append("\n")
                        .append("- Maximum rounds per suggestion: ").append(valueOrDefault(loopMap, "maxRounds", 0)).append("\n")
                        .append("- Focused review batches: ").append(valueOrDefault(loopMap, "batchCount", 0)).append("\n")
                        .append("- Review message: ").append(customerText(String.valueOf(valueOrDefault(loopMap, "message", "")))).append("\n");
            } else {
                report.append("- Message: ").append(valueOrDefault(criticMap, "message", "")).append("\n");
            }
            Object batches = criticMap.get("batches");
            if (batches instanceof List<?> values) {
                for (Object value : values) {
                    if (!(value instanceof Map<?, ?> batch) || Boolean.TRUE.equals(batch.get("success"))) continue;
                    report.append("- Review batch #").append(valueOrDefault(batch, "batchIndex", "?"))
                            .append(" could not be completed after ").append(valueOrDefault(batch, "attemptCount", 0))
                            .append(" attempts: ").append(valueOrDefault(batch, "errorReason", "Unknown batch failure."))
                            .append("\n");
                }
            }
            report.append('\n');
        } catch (Exception ex) {
            report.append("- Status: DEGRADED\n- Failed to parse evidence-review diagnostics: ")
                    .append(ex.getMessage()).append("\n\n");
        }
    }

    private void appendFinalArtifactAudit(StringBuilder report, PaperFinalAuditService.AuditResult audit) {
        report.append("## Final Artifact Audit\n\n")
                .append("- Status: ").append(audit.status()).append("\n");
        audit.counts().forEach((key, value) -> report.append("- ").append(key).append(": ").append(value).append("\n"));
        if (audit.issues().isEmpty()) {
            report.append("- Deterministic issues: 0\n\n");
            return;
        }
        report.append("- Deterministic issues: ").append(audit.issues().size()).append("\n");
        for (PaperFinalAuditService.AuditIssue issue : audit.issues()) {
            report.append("  - [").append(issue.severity()).append("] ")
                    .append(issue.code()).append(": ").append(issue.message()).append("\n");
        }
        report.append('\n');
    }

    private void appendSuggestionDecision(StringBuilder report, Suggestion suggestion) {
        if (suggestion.getPatchJson() == null || suggestion.getPatchJson().isBlank()) return;
        try {
            Map<String, Object> patch = objectMapper.readValue(suggestion.getPatchJson(), new TypeReference<Map<String, Object>>() {});
            Object closure = patch.get("citationClosure");
            if (closure instanceof Map<?, ?> decision) {
                report.append("- Evidence review: ").append(valueOrDefault(decision, "status", "REPORT_ONLY"))
                        .append(" after ").append(valueOrDefault(decision, "attempts", 0)).append(" round(s)")
                        .append(" - ").append(customerText(String.valueOf(valueOrDefault(decision, "reason", "No reason recorded."))))
                        .append("\n");
                return;
            }
            Object critic = patch.get("citationCritic");
            if (critic instanceof Map<?, ?> decision) {
                report.append("- Evidence review: ").append(valueOrDefault(decision, "verdict", "UNREVIEWED"))
                        .append(" - ").append(customerText(String.valueOf(valueOrDefault(decision, "reason", "No reason recorded."))))
                        .append("\n");
                return;
            }
            Object application = patch.get("applicationDecision");
            if (application instanceof Map<?, ?> decision) {
                report.append("- Application result: ").append(valueOrDefault(decision, "status", "REPORT_ONLY"))
                        .append(" - ").append(customerText(String.valueOf(valueOrDefault(decision, "reason", "No reason recorded."))))
                        .append("\n");
            }
        } catch (Exception ignored) {
            report.append("- Application decision: UNKNOWN - Failed to parse persisted decision metadata.\n");
        }
    }

    private void appendSectionFailureDistribution(StringBuilder report, List<PaperSection> sections) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (PaperSection section : sections) {
            if ("POLISHED".equalsIgnoreCase(section.getPolishStatus())) {
                continue;
            }
            String reasonCode = sectionReasonCode(section);
            if (reasonCode.isBlank()) {
                reasonCode = section.getPolishStatus() == null ? "NOT_POLISHED" : section.getPolishStatus();
            }
            distribution.put(reasonCode, distribution.getOrDefault(reasonCode, 0) + 1);
        }
        if (distribution.isEmpty()) {
            return;
        }
        report.append("\n### Section Failure Reasons\n\n");
        distribution.forEach((reason, count) -> report.append("- ").append(reason).append(": ").append(count).append("\n"));
        for (PaperSection section : sections) {
            if ("POLISHED".equalsIgnoreCase(section.getPolishStatus())) continue;
            List<String> lintCodes = sectionLintCodes(section);
            if (!lintCodes.isEmpty()) {
                report.append("- ").append(section.getTitle()).append(" lint blockers: ")
                        .append(lintCodes).append("\n");
            }
        }
    }

    private List<String> sectionLintCodes(PaperSection section) {
        if (section.getReviewJson() == null || section.getReviewJson().isBlank()) return List.of();
        try {
            Map<String, Object> review = objectMapper.readValue(
                    section.getReviewJson(), new TypeReference<Map<String, Object>>() {});
            Object rawIssues = review.get("lintIssues");
            if (!(rawIssues instanceof List<?> issues)) return List.of();
            Set<String> codes = new LinkedHashSet<>();
            for (Object value : issues) {
                if (value instanceof Map<?, ?> issue) {
                    codes.add(String.valueOf(valueOrDefault(issue, "code", "UNKNOWN")));
                }
            }
            return List.copyOf(codes);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String sectionReasonCode(PaperSection section) {
        String reviewJson = section.getReviewJson();
        if (reviewJson == null || reviewJson.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> review = objectMapper.readValue(reviewJson, new TypeReference<Map<String, Object>>() {});
            Object reasonCode = review.get("reasonCode");
            if (reasonCode == null) {
                reasonCode = review.get("reason");
            }
            return reasonCode == null ? "" : String.valueOf(reasonCode);
        } catch (Exception ignored) {
            return "review_json_parse_failed";
        }
    }

    private void appendIntroductionAnalysisDiagnostics(StringBuilder report, Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) {
            report.append("- No Introduction analysis recorded.\n");
            return;
        }
        try {
            Map<String, Object> ladder = objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
            report.append("- Generated by: ").append(valueOrDefault(ladder, "generatedBy", "unknown")).append("\n")
                    .append("- Degraded: ").append(valueOrDefault(ladder, "degraded", "unknown")).append("\n");
            Object diagnostics = ladder.get("introductionAnalysisDiagnostics");
            if (diagnostics instanceof Map<?, ?> map) {
                report.append("- LLM call mode: ").append(valueOrDefault(map, "llmCallMode", "single-call-or-legacy")).append("\n")
                        .append("- LLM call count: ").append(valueOrDefault(map, "llmCallCount", valueOrDefault(map, "llmAttemptCount", "unknown"))).append("\n")
                        .append("- Max tokens used: ").append(valueOrDefault(map, "maxTokensUsed", "part-specific")).append("\n")
                        .append("- Previous error: ").append(valueOrDefault(map, "previousError", "")).append("\n")
                        .append("- Raw LLM slot count: ").append(valueOrDefault(map, "rawSlotCount", "unknown")).append("\n")
                        .append("- Accepted LLM slot count: ").append(valueOrDefault(map, "acceptedLlmSlotCount", "unknown")).append("\n")
                        .append("- Minimum slot count: ").append(valueOrDefault(map, "minimumSlotCount", "unknown")).append("\n")
                        .append("- Final slot count: ").append(valueOrDefault(map, "finalSlotCount", "unknown")).append("\n")
                        .append("- Fallback-added slot count: ").append(valueOrDefault(map, "fallbackAddedSlotCount", "unknown")).append("\n")
                        .append("- Fallback reason: ").append(valueOrDefault(map, "fallbackReason", "")).append("\n");
                String rawPreview = String.valueOf(valueOrDefault(map, "rawTextPreview", ""));
                if (!rawPreview.isBlank()) {
                    report.append("- Raw LLM response preview:\n\n```json\n")
                            .append(rawPreview)
                            .append("\n```\n");
                }
            } else {
                report.append("- No detailed diagnostics recorded.\n");
            }
        } catch (Exception ex) {
            report.append("- Failed to parse Introduction diagnostics: ").append(ex.getMessage()).append("\n");
        }
    }

    private void appendGlobalReview(StringBuilder report, Long taskId) {
        report.append("## Whole-Paper Consistency Review\n\n");
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getGapMatrixJson() == null || analysis.getGapMatrixJson().isBlank()) {
            report.append("- No global review recorded.\n\n");
            return;
        }
        try {
            Map<String, Object> matrix = objectMapper.readValue(analysis.getGapMatrixJson(), new TypeReference<Map<String, Object>>() {});
            Object review = matrix.get("globalReview");
            if (!(review instanceof Map<?, ?> reviewMap)) {
                report.append("- No global review recorded.\n\n");
                return;
            }
            report.append("- Issues detected: ").append(valueOrDefault(reviewMap, "issueCount", "unknown")).append("\n")
                    .append("- Safe repairs attempted: ").append(valueOrDefault(reviewMap, "repairAttemptedCount", 0)).append("\n")
                    .append("- Issues resolved automatically: ").append(valueOrDefault(reviewMap, "resolvedIssueCount", 0)).append("\n")
                    .append("- Issues remaining in the report: ").append(valueOrDefault(reviewMap, "remainingIssueCount", valueOrDefault(reviewMap, "issueCount", "unknown"))).append("\n")
                    .append("- Repair status: ").append(valueOrDefault(reviewMap, "repairStatus", "REPORT_ONLY")).append("\n")
                    .append("- Suppressed unverified candidates: ").append(valueOrDefault(reviewMap, "suppressedIssueCount", 0)).append("\n")
                    .append("- Formula and symbol/dimension findings remain report-only; only verified local prose repairs may be applied.\n\n");
            Object warning = reviewMap.get("warning");
            if (warning != null && !String.valueOf(warning).isBlank()) {
                report.append("- Warning: ").append(warning).append("\n\n");
            }
            Object issues = reviewMap.get("issues");
            if (issues instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> issue)) continue;
                    report.append("- [").append(valueOrDefault(issue, "severity", "minor")).append("] ")
                            .append("[").append(valueOrDefault(issue, "resolutionStatus", "REPORT_ONLY")).append("] ")
                            .append(valueOrDefault(issue, "type", "LOGIC"))
                            .append(" sections=").append(valueOrDefault(issue, "sectionIds", List.of()))
                            .append(": ").append(valueOrDefault(issue, "message", "")).append("\n")
                            .append("  - Suggested fix: ").append(valueOrDefault(issue, "suggestedFix", "Manual review.")).append("\n");
                    Object resolutionReason = issue.get("resolutionReason");
                    if (resolutionReason != null && !String.valueOf(resolutionReason).isBlank()) {
                        report.append("  - Resolution: ").append(resolutionReason).append("\n");
                    }
                    Object evidence = issue.get("evidence");
                    if (evidence instanceof List<?> evidenceItems) {
                        for (Object evidenceItem : evidenceItems) {
                            if (!(evidenceItem instanceof Map<?, ?> evidenceMap)) continue;
                            report.append("  - Evidence section ").append(valueOrDefault(evidenceMap, "sectionOrder", "?"))
                                    .append(": `").append(valueOrDefault(evidenceMap, "quote", "")).append("`\n");
                        }
                    }
                }
            }
        } catch (Exception ex) {
            report.append("- Failed to parse global review: ").append(ex.getMessage()).append("\n");
        }
        report.append('\n');
    }

    private void appendCitationSlots(StringBuilder report, Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) {
            report.append("- No Introduction citation-slot analysis recorded.\n");
            return;
        }
        try {
            Map<String, Object> ladder = objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
            Object slots = ladder.get("citationSlots");
            if (!(slots instanceof List<?> list) || list.isEmpty()) {
                report.append("- No citation slots recorded.\n");
                return;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> slot)) continue;
                report.append("- **").append(valueOrDefault(slot, "category", "Citation slot")).append("**: ")
                        .append(valueOrDefault(slot, "claim", "")).append("\n")
                        .append("  - Need: ").append(valueOrDefault(slot, "citationNeed", "NEEDS_SUPPORT")).append("\n")
                        .append("  - Existing citation keys: ").append(valueOrDefault(slot, "existingCitationKeys", List.of())).append("\n")
                        .append("  - Queries: ").append(valueOrDefault(slot, "queries", List.of())).append("\n");
            }
        } catch (Exception ex) {
            report.append("- Failed to parse citation slots: ").append(ex.getMessage()).append("\n");
        }
    }

    private void appendUploadedBibDedupSummary(StringBuilder report, BibBuildResult bibResult) {
        report.append("\n## Uploaded Bibliography Deduplication\n\n")
                .append("- Uploaded bibliography entries parsed: ").append(bibResult.existingBibliographyCount()).append("\n")
                .append("- References used by accepted recommendations: ").append(bibResult.newReferenceCount() + bibResult.reusedReferenceCount()).append("\n")
                .append("- New references after DOI/title deduplication: ").append(bibResult.newReferenceCount()).append("\n")
                .append("- Existing uploaded references reused: ").append(bibResult.reusedReferenceCount()).append("\n\n");
        if (!bibResult.duplicateCards().isEmpty()) {
            report.append("Already-present or likely conference/journal versions reuse the uploaded bibliography entry and are excluded from `suggested-novel.bib`.\n\n");
            for (Map<String, Object> item : bibResult.duplicateCards()) {
                report.append("- ").append(item.get("title"))
                        .append(item.get("year") == null ? "" : " (" + item.get("year") + ")")
                        .append(item.get("bibKey") == null ? "" : " -> reused `" + item.get("bibKey") + "`")
                        .append("\n");
            }
            report.append('\n');
        }
    }

    private void appendSupplementalBibliography(StringBuilder report, Long taskId, Map<Long, List<LiteratureCard>> evidenceCards, int literatureLimit) {
        Set<Long> evidenceIds = evidenceCards.values().stream()
                .flatMap(List::stream)
                .map(LiteratureCard::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PaperTaskLiterature> supplemental = taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .filter(item -> item.getCardId() != null && !evidenceIds.contains(item.getCardId()))
                .filter(item -> item.getRelevanceScore() >= SUPPLEMENTAL_BIB_MIN_SCORE)
                .limit(literatureLimit)
                .toList();
        if (supplemental.isEmpty()) return;
        Map<Long, LiteratureCard> cardsById = literatureCards.findAllById(supplemental.stream().map(PaperTaskLiterature::getCardId).toList()).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity()));
        report.append("\n## Supplemental Bibliography Candidates\n\n")
                .append("These are selected real retrieval candidates listed for review when grounded Gap suggestions are sparse. They are not automatically cited or added to the merged bibliography.\n\n");
        for (PaperTaskLiterature item : supplemental) {
            LiteratureCard card = cardsById.get(item.getCardId());
            if (card == null) continue;
            report.append("- ").append(card.getTitle())
                    .append(card.getPublicationYear() == null ? "" : " (" + card.getPublicationYear() + ")")
                    .append(" — score ").append(String.format(java.util.Locale.ROOT, "%.2f", item.getRelevanceScore()))
                    .append(", query: `").append(item.getSourceQuery() == null ? "" : item.getSourceQuery()).append("`\n");
        }
        report.append('\n');
    }

    private void appendRetrievalDiagnostics(StringBuilder report, Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) {
            report.append("- No retrieval diagnostics recorded.\n");
            return;
        }
        try {
            Map<String, Object> ladder = objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
            Object diagnostics = ladder.get("retrievalDiagnostics");
            if (!(diagnostics instanceof Map<?, ?> map)) {
                report.append("- No retrieval diagnostics recorded.\n");
                return;
            }
            report.append("- Queries: ").append(valueOrDefault(map, "queries", List.of())).append("\n")
                    .append("- Source attempts: ").append(valueOrDefault(map, "sourceAttempts", "unknown")).append("\n")
                    .append("- Source failures: ").append(valueOrDefault(map, "sourceFailures", "unknown")).append("\n")
                    .append("- Raw candidates: ").append(valueOrDefault(map, "rawCandidateCount", "unknown")).append("\n")
                    .append("- Unique candidates: ").append(valueOrDefault(map, "uniqueCandidateCount", "unknown")).append("\n")
                    .append("- Selected candidates: ").append(valueOrDefault(map, "selectedCount", "unknown")).append("\n");
        } catch (Exception ex) {
            report.append("- Failed to parse retrieval diagnostics: ").append(ex.getMessage()).append("\n");
        }
    }

    private Object valueOrDefault(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String customerText(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)\\bevidence cards?\\s+\\d+(?:\\s*(?:,|and)\\s*\\d+)*", "the selected supporting papers")
                .replaceAll("(?i)\\bcards?\\s+\\d+(?:\\s*(?:,|and)\\s*\\d+)*", "the selected literature")
                .replaceAll("(?i)\\bcard[-\\s]?\\d+\\b", "the selected literature")
                .replaceAll("(?i)\\bslot[-\\s]?\\d+\\b", "the target claim")
                .replaceAll("(?i)the selected literature(?:\\s*(?:,|and)\\s*the selected literature)+", "the selected literature")
                .replaceAll("(?i)citation critic", "evidence review")
                .replaceAll("(?i)citation closure", "citation review")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<LatexLintIssue> validateAssembledTex(String tex, Set<String> existingBibKeys) {
        List<LatexLintIssue> issues = new ArrayList<>(maskingService.lint(tex));
        Set<String> knownKeys = existingBibKeys == null ? Set.of() : existingBibKeys;
        String activeTex = COMMENT_LINE_PATTERN.matcher(tex == null ? "" : tex).replaceAll("");
        Set<String> labels = new LinkedHashSet<>();
        Matcher labelMatcher = LABEL_PATTERN.matcher(activeTex);
        while (labelMatcher.find()) {
            labels.add(labelMatcher.group(1).trim());
        }
        Matcher refMatcher = REF_PATTERN.matcher(activeTex);
        while (refMatcher.find()) {
            String label = refMatcher.group(1).trim();
            if (!label.isBlank() && !labels.contains(label)) {
                issues.add(new LatexLintIssue(LatexLintIssue.Severity.MINOR, "REF_WITHOUT_LABEL", "Reference target not defined: " + label, refMatcher.start(), refMatcher.end()));
            }
        }
        Matcher matcher = BIB_KEY_PATTERN.matcher(activeTex);
        while (matcher.find()) {
            for (String key : matcher.group(1).split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isBlank() && !knownKeys.contains(trimmed)) {
                    issues.add(new LatexLintIssue(LatexLintIssue.Severity.MINOR, "CITE_NOT_IN_ORIGINAL_BIB", "Citation key not present in original bib: " + trimmed, matcher.start(), matcher.end()));
                }
            }
        }
        Matcher slotMarker = CITATION_SLOT_PATTERN.matcher(activeTex);
        while (slotMarker.find()) {
            issues.add(new LatexLintIssue(LatexLintIssue.Severity.BLOCKER, "RESIDUAL_CITATION_SLOT",
                    "Protected citation-slot marker was not replaced before export.", slotMarker.start(), slotMarker.end()));
        }
        return issues;
    }

    private Map<String, Object> saveArtifact(PaperTask task, String type, String filename, String content, String contentType, Map<String, Object> metadata) {
        int version = artifacts.findFirstByTaskIdAndTypeOrderByVersionDesc(task.getId(), type)
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        supersedeExistingArtifacts(task.getId(), type);
        String objectKey = storageService.storeArtifact(task.getUserId(), type, filename, content.getBytes(StandardCharsets.UTF_8), contentType);
        PaperTaskArtifact artifact = new PaperTaskArtifact(task.getId(), type, objectKey, version);
        Map<String, Object> meta = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        meta.put("filename", filename);
        meta.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        artifact.setMetadataJson(toJson(meta));
        artifact = artifacts.save(artifact);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", artifact.getId());
        result.put("type", artifact.getType());
        result.put("objectKey", artifact.getObjectKey());
        result.put("version", artifact.getVersion());
        return result;
    }

    private void supersedeExistingArtifacts(Long taskId, String type) {
        List<PaperTaskArtifact> existingArtifacts = artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> type.equals(artifact.getType()))
                .filter(artifact -> PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus()))
                .peek(artifact -> artifact.setArtifactStatus(PaperTaskArtifact.STATUS_SUPERSEDED))
                .toList();
        if (!existingArtifacts.isEmpty()) {
            artifacts.saveAll(existingArtifacts);
        }
    }

    private String uniqueBibKey(LiteratureCard card, Set<String> usedKeys) {
        String firstAuthor = authors(card).split(" and ")[0].replaceAll("[^A-Za-z0-9]", "");
        if (firstAuthor.isBlank()) firstAuthor = "paper";
        String year = card.getPublicationYear() == null ? "nd" : String.valueOf(card.getPublicationYear());
        String base = (firstAuthor + year).toLowerCase();
        String key = base;
        int suffix = 1;
        while (usedKeys.contains(key)) {
            key = base + suffix++;
        }
        return key;
    }

    private String authors(LiteratureCard card) {
        if (!notBlank(card.getAuthors())) return "Unknown";
        try {
            List<String> authors = objectMapper.readValue(card.getAuthors(), new TypeReference<List<String>>() {});
            return authors.isEmpty() ? "Unknown" : String.join(" and ", authors);
        } catch (Exception ex) {
            return card.getAuthors();
        }
    }

    private String escapeBib(String value) {
        return value == null ? "" : value.replace("{", "\\{").replace("}", "\\}");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
