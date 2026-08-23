package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ContextConfiguration(classes = PaperGapAnalysisServiceTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PaperGapAnalysisServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PaperTask.class, PaperTaskAnalysis.class, LiteratureCard.class, PaperTaskLiterature.class, Suggestion.class, SuggestionEvidence.class})
    @EnableJpaRepositories(basePackageClasses = {PaperTaskRepository.class, PaperTaskAnalysisRepository.class, LiteratureCardRepository.class, PaperTaskLiteratureRepository.class, SuggestionRepository.class, SuggestionEvidenceRepository.class})
    @Import({PaperGapAnalysisService.class, PaperPromptService.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        PaperModelClient paperModelClient() {
            return (systemPrompt, userPrompt, temperature, maxTokens) -> """
                    {
                      "suggestions": [
                        {
                          "track": "ADVOCACY",
                          "category": "RelatedWork",
                          "severity": "minor",
                          "statement": "Use retrieved work to position the method.",
                          "evidence": ["card-1"],
                          "targetSlotId": "slot-1"
                        },
                        {
                          "track": "ADVOCACY",
                          "category": "Experiment",
                          "severity": "major",
                          "statement": "Invent a missing experiment.",
                          "evidence": ["card-999"],
                          "applicable": true,
                          "patch": {"contentType":"A","anchor":"Experiments:end","latexSnippet":"Fake result."}
                        }
                      ]
                    }
                    """;
        }
    }

    private final PaperGapAnalysisService gapAnalysisService;
    private final PaperTaskRepository tasks;
    private final PaperTaskAnalysisRepository analyses;
    private final LiteratureCardRepository cards;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;

    @Autowired
    PaperGapAnalysisServiceTest(PaperGapAnalysisService gapAnalysisService,
                                PaperTaskRepository tasks,
                                PaperTaskAnalysisRepository analyses,
                                LiteratureCardRepository cards,
                                PaperTaskLiteratureRepository taskLiterature,
                                SuggestionRepository suggestions,
                                SuggestionEvidenceRepository evidenceRepository) {
        this.gapAnalysisService = gapAnalysisService;
        this.tasks = tasks;
        this.analyses = analyses;
        this.cards = cards;
        this.taskLiterature = taskLiterature;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
    }

    @Test
    void generateAndSavePersistsGroundedSuggestionsAndConvertsUngroundedAdvocacy() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(task.getId());
        analysis.setResearchProfileJson("{\"problem\":\"RAG\",\"method\":\"hybrid retrieval\"}");
        analysis.setConceptLadderJson("{\"citationSlots\":[{\"id\":\"slot-1\",\"category\":\"related work positioning\",\"claim\":\"Grounded related-work context needs support.\",\"sourceAnchor\":\"The retrieved literature directly supports the positioning of this proposed method in prior work.\"}]}");
        analyses.save(analysis);
        LiteratureCard card = new LiteratureCard("hash-rag", "Hybrid Retrieval for RAG");
        card.setAbstractText("Hybrid retrieval improves retrieval augmented generation grounding.");
        card.setAuthors("[\"A. Researcher\"]");
        card.setPublicationYear(2025);
        card.setDoi("10.1000/rag.2025");
        card.setAnalysisJson("{\"evidenceUse\":[{\"supports\":\"Hybrid retrieval directly supports grounded retrieval augmented generation.\",\"strength\":\"HIGH\"}]}");
        card = cards.save(card);
        Long cardId = card.getId();
        PaperTaskLiterature relation = new PaperTaskLiterature(task.getId(), cardId);
        relation.setSelected(true);
        relation.setRelevanceScore(0.9);
        relation.setNarrativeRole("advocacy");
        taskLiterature.save(relation);

        String introduction = "\\section{INTRODUCTION}\nThe retrieved literature directly supports the positioning of this proposed method in prior work.";
        LatexSection introductionSection = new LatexSection(0, 1, "\\section", true, "INTRODUCTION", LatexSectionRole.INTRO,
                0, introduction.length(), introduction);
        LatexDocument document = new LatexDocument("main.tex", "RAG Paper", List.of(), List.of(), "", "", List.of(introductionSection),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());

        List<GapSuggestionResult> results = gapAnalysisService.generateAndSave(
                task.getId(), "Sections:\n- [0] INTRODUCTION role=INTRO\n", "en", document);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).track()).isEqualTo("ADVOCACY");
        assertThat(results.get(0).applicable()).isTrue();
        assertThat(results.get(0).evidenceCardIds()).containsExactly(cardId);
        assertThat(results.get(0).patch()).containsEntry("targetSlotId", "slot-1")
                .containsEntry("anchor", "The retrieved literature directly supports the positioning of this proposed method in prior work.");
        assertThat(results.get(1).track()).isEqualTo("CRITIQUE");
        assertThat(results.get(1).applicable()).isFalse();
        assertThat(results.get(1).statement()).contains("Converted from ungrounded advocacy");
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId())).hasSize(2);
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(0).getStatus()).isEqualTo("ACCEPTED");
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(0).getPatchJson()).contains("AUTO_GROUNDED");
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(1).getStatus()).isEqualTo("PROPOSED");
        assertThat(evidenceRepository.findBySuggestionId(results.get(0).suggestionId())).singleElement()
                .satisfies(evidence -> assertThat(evidence.getCardId()).isEqualTo(cardId));
        LatexDocument marked = gapAnalysisService.markCitationSlots(document, results);
        assertThat(marked.sections().get(0).rawText()).contains("\\yanbancitationslot{" + results.get(0).suggestionId() + "}");
        assertThat(analyses.findByTaskId(task.getId()).orElseThrow().getGapMatrixJson())
                .contains("gap-analysis-v2-slot-driven", "rawModelResponse", "COMPLETE_JSON");
    }

    @Test
    void exposesDegradedEmptyAnalysisToTheOrchestrator() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "GAP_ANALYSIS", null));
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(task.getId());
        analysis.setGapMatrixJson("{\"analysisStatus\":\"DEGRADED\",\"suggestionCount\":0}");
        analyses.save(analysis);

        assertThat(gapAnalysisService.isDegradedWithoutSuggestions(task.getId())).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialCriticDecisionDefersAllEvidenceToCitationClosure() {
        PaperTask task = tasks.save(new PaperTask(1L, "Radar Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "GAP_ANALYSIS", null));
        String anchor = "Existing radar studies support target detection, while joint waveform design remains open.";
        String supportedClause = "Existing radar studies support target detection";
        String conceptLadder = """
                {"citationSlots":[{"id":"slot-1","category":"related work","claim":"Radar detection and joint waveform design.",
                "sourceAnchor":"Existing radar studies support target detection, while joint waveform design remains open."}]}
                """;
        String modelJson = """
                {"suggestions":[{"track":"ADVOCACY","category":"RelatedWork","statement":"Strengthen this positioning.",
                "evidence":["card-1","card-2"],"targetSlotId":"slot-1"}]}
                """;
        List<GapSuggestionResult> initial = gapAnalysisService.parseAndSave(
                task.getId(), modelJson, Set.of(1L, 2L), conceptLadder, "Sections:\n- [0] INTRODUCTION role=INTRO\n");
        Long suggestionId = initial.get(0).suggestionId();
        PaperCitationCriticService.CitationDecision decision = new PaperCitationCriticService.CitationDecision(
                suggestionId, "PARTIAL", List.of(1L), supportedClause, "Only the first clause is supported.");
        PaperCitationCriticService.CitationCriticResult criticResult = new PaperCitationCriticService.CitationCriticResult(
                Map.of(suggestionId, decision), false, "All batches completed.", "PASS", List.of());

        List<GapSuggestionResult> reviewed = ReflectionTestUtils.invokeMethod(
                gapAnalysisService, "applyCitationCritic", initial, criticResult);

        assertThat(reviewed).singleElement().satisfies(result -> {
            assertThat(result.applicable()).isFalse();
            assertThat(result.evidenceCardIds()).containsExactly(1L, 2L);
            assertThat(result.patch()).containsEntry("anchor", anchor)
                    .containsEntry("decisionSource", "CITATION_CLOSURE_REQUIRED");
        });
        Suggestion saved = suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(0);
        assertThat(saved.getStatus()).isEqualTo("PROPOSED");
        assertThat(evidenceRepository.findBySuggestionId(suggestionId))
                .extracting(SuggestionEvidence::getCardId)
                .containsExactlyInAnyOrder(1L, 2L);

        LatexSection section = new LatexSection(0, 1, "\\section", true, "INTRODUCTION", LatexSectionRole.INTRO,
                0, anchor.length(), anchor);
        LatexDocument document = new LatexDocument("main.tex", "Radar Paper", List.of(), List.of(), "", "", List.of(section),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
        LatexDocument marked = gapAnalysisService.markCitationSlots(document, reviewed);
        assertThat(marked.sections().get(0).rawText())
                .doesNotContain("\\yanbancitationslot{" + suggestionId + "}");
    }

    @Test
    void parseAndSaveIgnoresEvidenceOutsideAllowedCards() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        String json = """
                {"suggestions":[{"track":"ADVOCACY","category":"RelatedWork","statement":"Unsupported","evidence":["card-123"],"applicable":true,"patch":{"latexSnippet":"x"}}]}
                """;

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(task.getId(), json, Set.of(1L));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.track()).isEqualTo("CRITIQUE");
            assertThat(result.evidenceCardIds()).isEmpty();
            assertThat(result.applicable()).isFalse();
        });
    }

    @Test
    void parseAndSaveBuildsPatchFromSlotAndKeepsAllGroundedEvidence() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        String conceptLadder = """
                {"citationSlots":[{"id":"slot-8","category":"gap / unified framework","claim":"A unified modeling framework for polarimetric FDA-MIMO radar is still missing."}]}
                """;
        String json = """
                {"suggestions":[{
                  "track":"ADVOCACY",
                  "category":"RelatedWork",
                  "statement":"Strengthen the unified framework gap with recent polarimetric FDA-MIMO studies.",
                  "evidence":["card-1","card-2","card-3"],
                  "targetSlotId":"slot-8"
                }]}
                """;

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(
                task.getId(), json, Set.of(1L, 2L, 3L), conceptLadder, "Sections:\n- [0] INTRODUCTION role=INTRO\n");

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.applicable()).isTrue();
            assertThat(result.evidenceCardIds()).containsExactly(1L, 2L, 3L);
            assertThat(result.patch()).containsEntry("targetSlotId", "slot-8")
                    .containsEntry("sectionOrder", 0)
                    .containsEntry("sectionTitle", "INTRODUCTION")
                    .containsEntry("anchor", "A unified modeling framework for polarimetric FDA-MIMO radar is still missing.");
        });
        Suggestion saved = suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(0);
        assertThat(saved.getStatus()).isEqualTo("ACCEPTED");
        assertThat(saved.getPatchJson()).contains("MODEL_SLOT", "AUTO_GROUNDED");
        assertThat(evidenceRepository.findBySuggestionId(saved.getId())).hasSize(3);
    }

    @Test
    void parseAndSaveInfersUniqueSlotWhenModelOmitsTargetSlotId() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        String conceptLadder = """
                {"citationSlots":[
                  {"id":"slot-7","category":"constant modulus waveform design categories","claim":"Existing constant-modulus waveform methods include semidefinite relaxation and manifold optimization."},
                  {"id":"slot-9","category":"self protection jamming","claim":"Polarization processing suppresses a collocated self-protection jammer."}
                ]}
                """;
        String json = """
                {"suggestions":[{
                  "track":"ADVOCACY",
                  "category":"RelatedWork",
                  "statement":"Add recent constant-modulus waveform design using semidefinite relaxation and manifold optimization.",
                  "evidence":["card-1"]
                }]}
                """;

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(
                task.getId(), json, Set.of(1L), conceptLadder, "Sections:\n- [0] INTRODUCTION role=INTRO\n");

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.applicable()).isTrue();
            assertThat(result.patch()).containsEntry("targetSlotId", "slot-7")
                    .containsEntry("slotMatchMode", "INFERRED_SLOT");
        });
    }

    @Test
    void parseAndSaveKeepsSuggestionAsReportOnlyWhenSlotMatchIsAmbiguous() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        String conceptLadder = """
                {"citationSlots":[
                  {"id":"slot-1","category":"waveform background","claim":"Waveform diversity improves radar interference suppression."},
                  {"id":"slot-2","category":"waveform background","claim":"Waveform diversity improves radar jamming suppression."}
                ]}
                """;
        String json = """
                {"suggestions":[{
                  "track":"ADVOCACY",
                  "category":"RelatedWork",
                  "statement":"Add a citation about waveform diversity improving radar suppression.",
                  "evidence":["card-1"]
                }]}
                """;

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(
                task.getId(), json, Set.of(1L), conceptLadder, "Sections:\n- [0] INTRODUCTION role=INTRO\n");

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.applicable()).isFalse();
            assertThat(result.patch()).containsKey("applicationDecision");
            Map<?, ?> decision = (Map<?, ?>) result.patch().get("applicationDecision");
            assertThat(decision.get("status")).isEqualTo("NO_UNIQUE_CITATION_SLOT");
        });
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(0).getStatus()).isEqualTo("PROPOSED");
    }

    @Test
    void parseAndSaveRecoversCompleteSuggestionsFromTruncatedResponse() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        String conceptLadder = """
                {"citationSlots":[
                  {"id":"slot-1","category":"waveform background","claim":"Waveform diversity improves radar interference suppression."}
                ]}
                """;
        String truncated = """
                {"suggestions":[
                  {"track":"ADVOCACY","category":"RelatedWork","severity":"minor","statement":"Add evidence for waveform diversity.","evidence":["card-1"],"targetSlotId":"slot-1"},
                  {"track":"CRITIQUE","category":"Positioning","severity":"major","statement":"Compare the evaluation protocol with related work.","evidence":["card-2"],"targetSlotId":""},
                  {"track":"CRITIQUE","category":"Incomplete","severity":"
                """;

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(
                task.getId(), truncated, Set.of(1L, 2L), conceptLadder, "Sections:\n- [0] INTRODUCTION role=INTRO\n");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).applicable()).isTrue();
        assertThat(results.get(0).patch()).containsEntry("targetSlotId", "slot-1");
        assertThat(results.get(1).track()).isEqualTo("CRITIQUE");
        assertThat(results.get(1).applicable()).isFalse();
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId())).hasSize(2);
    }

    @Test
    void parseAndSaveKeepsAllSuggestionsWhenModelReturnsMoreThanEight() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        StringBuilder json = new StringBuilder("{\"suggestions\":[");
        for (int index = 1; index <= 10; index++) {
            if (index > 1) json.append(',');
            json.append("{\"track\":\"CRITIQUE\",\"category\":\"Check\",\"statement\":\"Suggestion ")
                    .append(index)
                    .append("\",\"evidence\":[]}");
        }
        json.append("]}");

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(task.getId(), json.toString(), Set.of());

        assertThat(results).hasSize(10);
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId())).hasSize(10);
    }

    @Test
    void parseFailureDoesNotDeleteExistingSuggestionsOrCreateGenericFallbacks() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        Suggestion existing = new Suggestion(task.getId(), "ADVOCACY", "RelatedWork", "Keep this valid suggestion.");
        existing.setApplicable(true);
        existing = suggestions.save(existing);
        evidenceRepository.save(new SuggestionEvidence(existing.getId(), 1L));

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(task.getId(), "{\"suggestions\":[{", Set.of(1L));

        assertThat(results).isEmpty();
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId())).singleElement()
                .satisfies(saved -> assertThat(saved.getStatement()).isEqualTo("Keep this valid suggestion."));
        assertThat(evidenceRepository.findBySuggestionId(existing.getId())).hasSize(1);
    }
}
