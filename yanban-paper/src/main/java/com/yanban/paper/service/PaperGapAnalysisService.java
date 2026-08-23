package com.yanban.paper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceId;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperGapAnalysisService {

    private static final Pattern INTRO_SECTION = Pattern.compile("(?m)^- \\[(\\d+)]\\s+(.+?)\\s+role=INTRO\\s*$");
    private static final Pattern CITE_COMMAND = Pattern.compile("\\\\(?:cite|citep|citet|citeauthor|citeyear|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{[^{}]+}");
    private static final Pattern LEADING_SECTION_COMMAND = Pattern.compile("^\\\\(?:section|subsection|subsubsection)\\*?\\s*\\{[^{}]+}\\s*");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Set<String> MATCH_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "by", "can", "for", "from", "has", "have",
            "in", "into", "is", "it", "of", "on", "or", "that", "the", "their", "these", "this", "to", "using", "with");

    private final PaperTaskRepository tasks;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final LiteratureCardRepository cards;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PaperCitationCriticService> citationCriticProvider;

    public PaperGapAnalysisService(PaperTaskRepository tasks,
                                   PaperTaskAnalysisRepository analyses,
                                   PaperTaskLiteratureRepository taskLiterature,
                                   LiteratureCardRepository cards,
                                   SuggestionRepository suggestions,
                                   SuggestionEvidenceRepository evidenceRepository,
                                   PaperPromptService promptService,
                                   PaperModelClient modelClient,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<PaperCitationCriticService> citationCriticProvider) {
        this.tasks = tasks;
        this.analyses = analyses;
        this.taskLiterature = taskLiterature;
        this.cards = cards;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.citationCriticProvider = citationCriticProvider;
    }

    @Transactional
    public List<GapSuggestionResult> generateAndSave(Long taskId, String structureSummary, String targetLanguage) {
        return generateAndSave(taskId, structureSummary, targetLanguage, null);
    }

    @Transactional
    public List<GapSuggestionResult> generateAndSave(Long taskId,
                                                     String structureSummary,
                                                     String targetLanguage,
                                                     LatexDocument document) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElseGet(() -> new PaperTaskAnalysis(taskId));
        List<PaperTaskLiterature> selectedRelations = taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .toList();
        Map<Long, LiteratureCard> selectedCards = cards.findAllById(selectedRelations.stream().map(PaperTaskLiterature::getCardId).toList())
                .stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        String prompt = promptService.render("gap-analysis", Map.of(
                "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                "paperTitle", task.getTitle(),
                "researchProfile", blankToDefault(analysis.getResearchProfileJson(), "{}"),
                "conceptLadder", blankToDefault(analysis.getConceptLadderJson(), "{}"),
                "literatureCandidates", renderCandidates(selectedRelations, selectedCards),
                "structureSummary", blankToDefault(structureSummary, "No additional structure issues provided.")
        ));
        String initialText;
        String modelError = "";
        try {
            initialText = modelClient.complete("You return strict JSON only and never invent evidence.", prompt, 0.2, 4096);
        } catch (Exception ex) {
            initialText = "";
            modelError = rootMessage(ex);
        }
        SuggestionPayload initialPayload = suggestionPayload(initialText);
        boolean retryAttempted = !"COMPLETE_JSON".equals(initialPayload.parseMode());
        String retryText = "";
        SuggestionPayload payload = initialPayload;
        String text = initialText;
        if (retryAttempted) {
            try {
                retryText = modelClient.complete(
                        "You repair incomplete JSON. Return one complete strict JSON object only.",
                        prompt + "\n\nThe previous response was incomplete. Repeat the analysis as one complete JSON object. Keep statements concise and close every array and object.",
                        0.1,
                        4096);
                SuggestionPayload retryPayload = suggestionPayload(retryText);
                if ("COMPLETE_JSON".equals(retryPayload.parseMode())
                        || retryPayload.suggestions().size() > initialPayload.suggestions().size()) {
                    payload = retryPayload;
                    text = retryText;
                }
            } catch (Exception ex) {
                if (modelError.isBlank()) modelError = rootMessage(ex);
            }
        }
        List<GapSuggestionResult> results = parseAndSave(
                taskId,
                text,
                selectedCards.keySet(),
                analysis.getConceptLadderJson(),
                structureSummary,
                selectedRelations.stream().collect(Collectors.toMap(
                        PaperTaskLiterature::getCardId,
                        item -> item.getRelevanceScore() == null ? 0.0 : item.getRelevanceScore(),
                        Math::max,
                        LinkedHashMap::new)),
                selectedCards,
                introductionText(document));
        PaperCitationCriticService.CitationCriticResult criticResult = null;
        PaperCitationCriticService citationCritic = citationCriticProvider == null
                ? null : citationCriticProvider.getIfAvailable();
        if (citationCritic != null && !results.isEmpty()) {
            criticResult = citationCritic.review(results, selectedCards);
            results = applyCitationCritic(results, criticResult);
        }
        Map<String, Object> gapAnalysis = new LinkedHashMap<>();
        gapAnalysis.put("generatedBy", "gap-analysis-v2-slot-driven");
        gapAnalysis.put("analysisStatus", "COMPLETE_JSON".equals(payload.parseMode()) ? "COMPLETED"
                : payload.suggestions().isEmpty() ? "DEGRADED" : "PARTIAL_RECOVERY");
        gapAnalysis.put("parseMode", payload.parseMode());
        gapAnalysis.put("responseComplete", "COMPLETE_JSON".equals(payload.parseMode()));
        gapAnalysis.put("retryAttempted", retryAttempted);
        gapAnalysis.put("modelError", modelError);
        gapAnalysis.put("parsedSuggestionCount", payload.suggestions().size());
        gapAnalysis.put("suggestionCount", results.size());
        gapAnalysis.put("suggestions", results);
        gapAnalysis.put("citationCritic", criticResult == null
                ? Map.of("generatedBy", "paper-citation-critic-v1", "enabled", false)
                : criticResult.summary());
        gapAnalysis.put("rawModelResponse", truncate(text, 6000));
        if (retryAttempted) {
            gapAnalysis.put("rawInitialResponse", truncate(initialText, 6000));
            gapAnalysis.put("rawRetryResponse", truncate(retryText, 6000));
        }
        analysis.setGapMatrixJson(toJson(gapAnalysis));
        analyses.save(analysis);
        return results;
    }

    @Transactional(readOnly = true)
    public boolean isDegradedWithoutSuggestions(Long taskId) {
        String gapMatrix = analyses.findByTaskId(taskId)
                .map(PaperTaskAnalysis::getGapMatrixJson)
                .orElse("");
        if (gapMatrix == null || gapMatrix.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(gapMatrix);
            return "DEGRADED".equals(root.path("analysisStatus").asText())
                    && root.path("suggestionCount").asInt(0) == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Transactional
    public List<GapSuggestionResult> parseAndSave(Long taskId, String modelText, Set<Long> allowedCardIds) {
        return parseAndSave(taskId, modelText, allowedCardIds, "{}", "");
    }

    @Transactional
    public List<GapSuggestionResult> parseAndSave(Long taskId,
                                                  String modelText,
                                                  Set<Long> allowedCardIds,
                                                  String conceptLadderJson,
                                                  String structureSummary) {
        return parseAndSave(taskId, modelText, allowedCardIds, conceptLadderJson, structureSummary, Map.of(), Map.of(), "");
    }

    private List<GapSuggestionResult> parseAndSave(Long taskId,
                                                   String modelText,
                                                   Set<Long> allowedCardIds,
                                                   String conceptLadderJson,
                                                   String structureSummary,
                                                   Map<Long, Double> evidenceScores,
                                                   Map<Long, LiteratureCard> evidenceCards,
                                                   String anchorSourceText) {
        SuggestionPayload payload = suggestionPayload(modelText);
        if (payload.suggestions().isEmpty()) {
            return List.of();
        }

        List<Suggestion> existing = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        if (!existing.isEmpty()) {
            evidenceRepository.deleteBySuggestionIdIn(existing.stream().map(Suggestion::getId).toList());
            suggestions.deleteByTaskId(taskId);
            suggestions.flush();
        }
        Map<String, CitationSlot> citationSlots = citationSlots(conceptLadderJson, anchorSourceText);
        SectionTarget introduction = introductionSection(structureSummary);
        List<GapSuggestionResult> results = new ArrayList<>();
        for (JsonNode node : payload.suggestions()) {
            NormalizedSuggestion normalized = normalize(
                    node,
                    allowedCardIds == null ? Set.of() : allowedCardIds,
                    citationSlots,
                    introduction,
                    evidenceScores,
                    evidenceCards);
            Suggestion suggestion = new Suggestion(taskId, normalized.track(), normalized.category(), normalized.statement());
            suggestion.setSeverity(normalized.severity());
            suggestion.setApplicable(normalized.applicable());
            Map<String, Object> persistedPatch = new LinkedHashMap<>(normalized.patch());
            boolean autoAccepted = isAutoApplicable(normalized);
            if (autoAccepted) {
                suggestion.setStatus("ACCEPTED");
                persistedPatch.put("decisionSource", "AUTO_GROUNDED");
                persistedPatch.put("targetSlotId", normalized.targetSlotId());
                persistedPatch.put("slotMatchMode", normalized.slotMatchMode());
            } else {
                persistedPatch.put("applicationDecision", applicationDecision(normalized));
            }
            suggestion.setPatchJson(persistedPatch.isEmpty() ? null : toJson(persistedPatch));
            suggestion = suggestions.save(suggestion);
            for (Long cardId : normalized.evidenceCardIds()) {
                evidenceRepository.save(new SuggestionEvidence(suggestion.getId(), cardId));
            }
            results.add(new GapSuggestionResult(
                    suggestion.getId(),
                    suggestion.getTrack(),
                    suggestion.getCategory(),
                    suggestion.getSeverity(),
                    suggestion.getStatement(),
                    normalized.evidenceCardIds(),
                    Boolean.TRUE.equals(suggestion.getApplicable()),
                    Map.copyOf(persistedPatch)
            ));
        }
        return results;
    }

    private List<GapSuggestionResult> applyCitationCritic(
            List<GapSuggestionResult> results,
            PaperCitationCriticService.CitationCriticResult criticResult) {
        Map<Long, Suggestion> persisted = suggestions.findAllById(results.stream()
                        .map(GapSuggestionResult::suggestionId)
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .stream()
                .collect(Collectors.toMap(Suggestion::getId, Function.identity()));
        List<GapSuggestionResult> reviewed = new ArrayList<>();
        for (GapSuggestionResult result : results) {
            PaperCitationCriticService.CitationDecision decision = criticResult.decisions().get(result.suggestionId());
            if (!result.applicable() || decision == null) {
                reviewed.add(result);
                continue;
            }
            Map<String, Object> patch = new LinkedHashMap<>(result.patch() == null ? Map.of() : result.patch());
            patch.put("citationCritic", decision.asMap());
            Suggestion suggestion = persisted.get(result.suggestionId());
            List<Long> evidenceIds = result.evidenceCardIds();
            boolean supported = decision.supported() && "SUPPORTED".equals(decision.verdict());
            if (supported) {
                if ("PARTIAL".equals(decision.verdict())) {
                    patch.put("anchor", decision.supportedAnchor());
                    patch.put("citationScope", "SUPPORTED_CLAUSE");
                }
                Set<Long> accepted = new LinkedHashSet<>(decision.acceptedEvidenceCardIds());
                for (Long cardId : evidenceIds) {
                    if (!accepted.contains(cardId)) {
                        evidenceRepository.deleteById(new SuggestionEvidenceId(result.suggestionId(), cardId));
                    }
                }
                evidenceIds = evidenceIds.stream().filter(accepted::contains).toList();
                patch.put("decisionSource", "AUTO_CRITIC_VERIFIED");
            } else {
                patch.put("decisionSource", "PARTIAL".equals(decision.verdict())
                        ? "CITATION_CLOSURE_REQUIRED"
                        : "CITATION_CRITIC_WITHHELD");
            }
            if (suggestion != null) {
                suggestion.setApplicable(supported);
                suggestion.setStatus(supported ? "ACCEPTED" : "PROPOSED");
                suggestion.setPatchJson(toJson(patch));
                suggestions.save(suggestion);
            }
            reviewed.add(new GapSuggestionResult(
                    result.suggestionId(), result.track(), result.category(), result.severity(), result.statement(),
                    evidenceIds, supported, Map.copyOf(patch)));
        }
        return reviewed;
    }

    private Map<String, Object> applicationDecision(NormalizedSuggestion suggestion) {
        if (!"ADVOCACY".equals(suggestion.track())) {
            return Map.of("status", "CRITIQUE_ONLY", "reason", "This suggestion is report-only critique and does not request a citation patch.");
        }
        if (suggestion.evidenceCardIds().isEmpty()) {
            return Map.of("status", "NO_GROUNDED_EVIDENCE", "reason", "No production-grade evidence card supports automatic insertion.");
        }
        if (suggestion.targetSlotId().isBlank()) {
            return Map.of("status", "NO_UNIQUE_CITATION_SLOT", "reason", "The suggestion could not be matched to one unique Introduction citation slot.");
        }
        return Map.of("status", "NO_STABLE_ANCHOR", "reason", "The selected citation slot has no unique stable manuscript anchor.");
    }

    private SuggestionPayload suggestionPayload(String modelText) {
        JsonNode root = readRoot(modelText);
        JsonNode array = root.path("suggestions");
        if (array.isArray()) {
            List<JsonNode> complete = new ArrayList<>();
            array.forEach(node -> {
                if (node.isObject()) complete.add(node);
            });
            return new SuggestionPayload(List.copyOf(complete), "COMPLETE_JSON");
        }
        List<JsonNode> recovered = recoverCompleteSuggestions(modelText);
        return new SuggestionPayload(
                recovered,
                recovered.isEmpty() ? "INVALID_OR_EMPTY" : "TRUNCATED_RECOVERY");
    }

    private List<JsonNode> recoverCompleteSuggestions(String modelText) {
        if (modelText == null || modelText.isBlank()) return List.of();
        int keyIndex = modelText.indexOf("\"suggestions\"");
        if (keyIndex < 0) return List.of();
        int arrayStart = modelText.indexOf('[', keyIndex);
        if (arrayStart < 0) return List.of();

        List<JsonNode> recovered = new ArrayList<>();
        int cursor = arrayStart + 1;
        while (cursor < modelText.length()) {
            int objectStart = modelText.indexOf('{', cursor);
            if (objectStart < 0) break;
            int objectEnd = completeObjectEnd(modelText, objectStart);
            if (objectEnd < 0) break;
            try {
                JsonNode node = objectMapper.readTree(modelText.substring(objectStart, objectEnd + 1));
                if (node.isObject()) recovered.add(node);
            } catch (JsonProcessingException ignored) {
                // Continue after a malformed item so later complete suggestions can still be considered.
            }
            cursor = objectEnd + 1;
        }
        return List.copyOf(recovered);
    }

    private int completeObjectEnd(String text, int objectStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = objectStart; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private boolean isAutoApplicable(NormalizedSuggestion suggestion) {
        if (!"ADVOCACY".equals(suggestion.track())
                || !suggestion.applicable()
                || suggestion.evidenceCardIds().isEmpty()
                || suggestion.patch().isEmpty()) {
            return false;
        }
        Object anchorValue = suggestion.patch().get("anchor");
        String anchor = anchorValue == null ? "" : String.valueOf(anchorValue).trim();
        return anchor.length() >= 8 && !anchor.contains(":start") && !anchor.contains(":end");
    }

    private NormalizedSuggestion normalize(JsonNode node,
                                           Set<Long> allowedCardIds,
                                           Map<String, CitationSlot> citationSlots,
                                           SectionTarget introduction,
                                           Map<Long, Double> evidenceScores,
                                           Map<Long, LiteratureCard> evidenceCards) {
        String track = uppercaseOrDefault(text(node, "track"), "CRITIQUE");
        if (!track.equals("ADVOCACY") && !track.equals("CRITIQUE")) {
            track = "CRITIQUE";
        }
        String category = blankToDefault(text(node, "category"), "General");
        String severity = blankToDefault(text(node, "severity"), "minor").toLowerCase(Locale.ROOT);
        String statement = blankToDefault(text(node, "statement"), "No statement provided.");
        List<Long> evidenceCardIds = validEvidence(node.path("evidence"), allowedCardIds, evidenceScores);
        boolean requestedApplicable = node.path("applicable").asBoolean(false);
        Map<String, Object> patch = patchMap(node.path("patch"));
        CitationSlotMatch slotMatch = citationSlotMatch(node, statement, citationSlots, patch);
        if ("ADVOCACY".equals(track) && slotMatch != null && evidenceCards != null && !evidenceCards.isEmpty()) {
            evidenceCardIds = evidenceCardIds.stream()
                    .filter(cardId -> isGroundedEvidenceCard(evidenceCards.get(cardId)))
                    .toList();
        }
        boolean hasGrounding = !evidenceCardIds.isEmpty();

        if (!hasGrounding && "ADVOCACY".equals(track)) {
            track = "CRITIQUE";
            requestedApplicable = false;
            patch = Map.of();
            statement = "[Converted from ungrounded advocacy] " + statement;
        }
        if (!"ADVOCACY".equals(track)) {
            requestedApplicable = false;
            patch = Map.of();
        }
        if ("ADVOCACY".equals(track) && hasGrounding && slotMatch != null) {
            patch = deterministicPatch(slotMatch.slot(), slotMatch.mode(), introduction);
            requestedApplicable = true;
        }
        if ("ADVOCACY".equals(track) && requestedApplicable && patch.isEmpty()) {
            requestedApplicable = false;
        }
        return new NormalizedSuggestion(
                track,
                category,
                severity,
                statement,
                evidenceCardIds,
                requestedApplicable,
                patch,
                slotMatch == null ? "" : slotMatch.slot().id(),
                slotMatch == null ? "" : slotMatch.mode());
    }

    private boolean isGroundedEvidenceCard(LiteratureCard card) {
        if (card == null
                || card.getTitle() == null || card.getTitle().isBlank()
                || card.getAbstractText() == null || card.getAbstractText().isBlank()
                || card.getAuthors() == null || card.getAuthors().isBlank() || "[]".equals(card.getAuthors().trim())
                || card.getPublicationYear() == null
                || firstNonBlank(card.getDoi(), card.getArxivId(), card.getOpenAlexId(), card.getS2Id(), card.getUrl()).isBlank()) {
            return false;
        }
        JsonNode analysis = readRoot(card.getAnalysisJson());
        JsonNode evidenceUse = analysis.path("evidenceUse");
        if (!evidenceUse.isArray()) return false;
        for (JsonNode evidence : evidenceUse) {
            String supports = text(evidence, "supports");
            String strength = uppercaseOrDefault(text(evidence, "strength"), "LOW");
            if (supports.length() >= 12 && ("HIGH".equals(strength) || "MEDIUM".equals(strength))) {
                return true;
            }
        }
        return false;
    }

    private CitationSlotMatch citationSlotMatch(JsonNode node,
                                                String statement,
                                                Map<String, CitationSlot> citationSlots,
                                                Map<String, Object> patch) {
        if (citationSlots.isEmpty()) return null;
        String requested = firstNonBlank(
                text(node, "targetSlotId"),
                text(node, "slotId"),
                patch.get("targetSlotId") == null ? "" : String.valueOf(patch.get("targetSlotId")));
        CitationSlot direct = citationSlots.get(requested);
        if (direct != null) return new CitationSlotMatch(direct, "MODEL_SLOT");

        List<Map.Entry<CitationSlot, Double>> ranked = citationSlots.values().stream()
                .map(slot -> Map.entry(slot, slotSimilarity(statement, slot)))
                .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                .toList();
        if (ranked.isEmpty() || ranked.get(0).getValue() < 0.42) return null;
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : -1.0;
        if (second >= 0 && ranked.get(0).getValue() - second < 0.06) return null;
        return new CitationSlotMatch(ranked.get(0).getKey(), "INFERRED_SLOT");
    }

    private double slotSimilarity(String statement, CitationSlot slot) {
        Set<String> left = matchTokens(statement);
        Set<String> right = matchTokens(slot.category() + " " + slot.claim());
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        long overlap = left.stream().filter(right::contains).count();
        if (overlap == 0) return 0.0;
        double dice = (2.0 * overlap) / (left.size() + right.size());
        double coverage = overlap / (double) Math.min(left.size(), right.size());
        return 0.7 * dice + 0.3 * coverage;
    }

    private Set<String> matchTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!MATCH_STOP_WORDS.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    private Map<String, Object> deterministicPatch(CitationSlot slot, String matchMode, SectionTarget introduction) {
        if (slot.sourceAnchor() == null || slot.sourceAnchor().isBlank()) return Map.of();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("contentType", "CITATION");
        patch.put("targetSlotId", slot.id());
        patch.put("sectionOrder", introduction.orderIndex());
        patch.put("sectionTitle", introduction.title());
        patch.put("anchor", slot.sourceAnchor());
        patch.put("slotMatchMode", matchMode);
        return patch;
    }

    private Map<String, CitationSlot> citationSlots(String conceptLadderJson, String anchorSourceText) {
        JsonNode root = readRoot(conceptLadderJson);
        JsonNode values = root.path("citationSlots");
        if (!values.isArray()) return Map.of();
        Map<String, CitationSlot> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String id = text(value, "id");
            String claim = text(value, "claim");
            if (id.isBlank() || claim.length() < 8) continue;
            String sourceAnchor = text(value, "sourceAnchor");
            if (anchorSourceText != null && !anchorSourceText.isBlank()) {
                sourceAnchor = uniqueSourceAnchor(anchorSourceText, sourceAnchor, claim);
            } else if (sourceAnchor.isBlank()) {
                sourceAnchor = claim;
            }
            result.putIfAbsent(id, new CitationSlot(id, text(value, "category"), claim, sourceAnchor));
        }
        return result;
    }

    public LatexDocument markCitationSlots(LatexDocument document, List<GapSuggestionResult> suggestionResults) {
        if (document == null || suggestionResults == null || suggestionResults.isEmpty()) return document;
        List<LatexSection> markedSections = new ArrayList<>();
        for (LatexSection section : document.sections()) {
            String markedText = section.rawText();
            for (GapSuggestionResult suggestion : suggestionResults) {
                if (suggestion == null || suggestion.suggestionId() == null || !suggestion.applicable()) continue;
                Map<String, Object> patch = suggestion.patch() == null ? Map.of() : suggestion.patch();
                if (!targetsSection(section, patch)) continue;
                String anchor = stringValue(patch.get("anchor"));
                String marker = citationSlotMarker(suggestion.suggestionId());
                if (anchor.isBlank() || markedText.contains(marker)) continue;
                int anchorIndex = uniqueOccurrence(markedText, anchor);
                if (anchorIndex < 0) continue;
                String anchored = markedText.substring(anchorIndex, anchorIndex + anchor.length());
                String replacement = appendCitationMarker(anchored, marker);
                markedText = markedText.substring(0, anchorIndex) + replacement + markedText.substring(anchorIndex + anchor.length());
            }
            markedSections.add(new LatexSection(
                    section.orderIndex(), section.level(), section.command(), section.numbered(), section.title(), section.role(),
                    section.startOffset(), section.endOffset(), markedText));
        }
        return new LatexDocument(
                document.sourcePath(), document.title(), document.authors(), document.keywords(), document.preamble(),
                document.frontMatter(), List.copyOf(markedSections), document.protectedSpans(), document.floats(),
                document.citationUsages(), document.crossReferences(), document.bibliography(), document.lintIssues());
    }

    private boolean targetsSection(LatexSection section, Map<String, Object> patch) {
        Integer order = integerValue(patch.get("sectionOrder"));
        if (order != null) return order == section.orderIndex();
        String title = stringValue(patch.get("sectionTitle"));
        return !title.isBlank() && normalizeForAnchor(title).equals(normalizeForAnchor(section.title()));
    }

    private String appendCitationMarker(String anchor, String marker) {
        int insertion = anchor.length();
        while (insertion > 0 && Character.isWhitespace(anchor.charAt(insertion - 1))) insertion--;
        if (insertion > 0 && ".,;:!?".indexOf(anchor.charAt(insertion - 1)) >= 0) insertion--;
        return anchor.substring(0, insertion) + " " + marker + anchor.substring(insertion);
    }

    private int uniqueOccurrence(String text, String needle) {
        int first = text == null || needle == null || needle.isBlank() ? -1 : text.indexOf(needle);
        if (first < 0) return -1;
        return text.indexOf(needle, first + needle.length()) < 0 ? first : -1;
    }

    private String citationSlotMarker(Long suggestionId) {
        return "\\yanbancitationslot{" + suggestionId + "}";
    }

    private String introductionText(LatexDocument document) {
        if (document == null || document.sections() == null) return "";
        return document.sections().stream()
                .filter(section -> section.role() == LatexSectionRole.INTRO
                        || (section.title() != null && section.title().toLowerCase(Locale.ROOT).contains("intro")))
                .findFirst()
                .map(LatexSection::rawText)
                .orElse("");
    }

    private String uniqueSourceAnchor(String sourceText, String requestedAnchor, String claim) {
        if (requestedAnchor != null && !requestedAnchor.isBlank() && uniqueOccurrence(sourceText, requestedAnchor) >= 0) {
            return requestedAnchor;
        }
        List<String> sentences = sourceSentences(sourceText);
        if (requestedAnchor != null && !requestedAnchor.isBlank()) {
            String normalizedRequested = normalizeWhitespace(requestedAnchor);
            List<String> equivalent = sentences.stream()
                    .filter(sentence -> normalizeWhitespace(sentence).equals(normalizedRequested))
                    .toList();
            if (equivalent.size() == 1) return equivalent.get(0);
        }
        List<Map.Entry<String, Double>> ranked = sentences.stream()
                .map(sentence -> Map.entry(sentence, anchorSimilarity(claim, sentence)))
                .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                .toList();
        if (ranked.isEmpty() || ranked.get(0).getValue() < 0.38) return "";
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : -1.0;
        if (second >= 0 && ranked.get(0).getValue() - second < 0.05) return "";
        return ranked.get(0).getKey();
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private List<String> sourceSentences(String sourceText) {
        String source = sourceText == null ? "" : sourceText;
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(source);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = LEADING_SECTION_COMMAND.matcher(source.substring(start, end).trim()).replaceFirst("").trim();
            if (matchTokens(searchableAnchor(sentence)).size() >= 4) sentences.add(sentence);
        }
        return sentences;
    }

    private double anchorSimilarity(String claim, String sourceSentence) {
        Set<String> left = matchTokens(claim);
        Set<String> right = matchTokens(searchableAnchor(sourceSentence));
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        long overlap = left.stream().filter(right::contains).count();
        if (overlap == 0) return 0.0;
        double dice = (2.0 * overlap) / (left.size() + right.size());
        double coverage = overlap / (double) Math.min(left.size(), right.size());
        return 0.7 * dice + 0.3 * coverage;
    }

    private String searchableAnchor(String sentence) {
        return CITE_COMMAND.matcher(sentence == null ? "" : sentence).replaceAll(" ")
                .replaceAll("\\\\[a-zA-Z]+\\*?(?:\\s*\\[[^]]*])?\\s*\\{([^{}]*)}", "$1");
    }

    private String normalizeForAnchor(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private SectionTarget introductionSection(String structureSummary) {
        Matcher matcher = INTRO_SECTION.matcher(structureSummary == null ? "" : structureSummary);
        if (matcher.find()) {
            return new SectionTarget(Integer.parseInt(matcher.group(1)), matcher.group(2).trim());
        }
        return new SectionTarget(0, "INTRODUCTION");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private List<Long> validEvidence(JsonNode evidence, Set<Long> allowedCardIds, Map<Long, Double> evidenceScores) {
        if (!evidence.isArray()) return List.of();
        Set<Long> valid = new LinkedHashSet<>();
        for (JsonNode item : evidence) {
            Long cardId = parseCardId(item.asText(""));
            if (cardId != null && allowedCardIds.contains(cardId)) {
                valid.add(cardId);
            }
        }
        if (evidenceScores == null || evidenceScores.isEmpty()) return List.copyOf(valid);
        return valid.stream()
                .sorted((left, right) -> Double.compare(
                        evidenceScores.getOrDefault(right, 0.0),
                        evidenceScores.getOrDefault(left, 0.0)))
                .toList();
    }

    private Long parseCardId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.startsWith("card-")) value = value.substring("card-".length());
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> patchMap(JsonNode patch) {
        if (patch == null || !patch.isObject()) return Map.of();
        try {
            return objectMapper.convertValue(patch, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private JsonNode readRoot(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String json = extractJsonObject(modelText);
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String renderCandidates(List<PaperTaskLiterature> relations, Map<Long, LiteratureCard> selectedCards) {
        StringBuilder builder = new StringBuilder();
        for (PaperTaskLiterature relation : relations) {
            LiteratureCard card = selectedCards.get(relation.getCardId());
            if (card == null) continue;
            builder.append("- card-").append(card.getId())
                    .append(" | score=").append(relation.getRelevanceScore())
                    .append(" | role=").append(relation.getNarrativeRole())
                    .append(" | slot=").append(relation.getLadderNode())
                    .append(" | sourceQuery=").append(relation.getSourceQuery())
                    .append(" | title=").append(card.getTitle())
                    .append(" | year=").append(card.getPublicationYear())
                    .append(" | venue=").append(card.getVenue())
                    .append("\n  abstract=").append(truncate(card.getAbstractText(), 700))
                    .append("\n  analysis=").append(truncate(card.getAnalysisJson(), 700))
                    .append('\n');
        }
        return builder.isEmpty() ? "No selected literature cards." : builder.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String uppercaseOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + " ...";
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        if (current == null) return "";
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private record NormalizedSuggestion(
            String track,
            String category,
            String severity,
            String statement,
            List<Long> evidenceCardIds,
            boolean applicable,
            Map<String, Object> patch,
            String targetSlotId,
            String slotMatchMode
    ) {
    }

    private record CitationSlot(String id, String category, String claim, String sourceAnchor) {}
    private record CitationSlotMatch(CitationSlot slot, String mode) {}
    private record SectionTarget(int orderIndex, String title) {}
    private record SuggestionPayload(List<JsonNode> suggestions, String parseMode) {}
}
