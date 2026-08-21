package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.SimpleKnowledgeSearchService;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = RagModeComparisonDatabaseEvalTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RagModeComparisonDatabaseEvalTest {

    private static final Path FIXTURE_ROOT = Path.of("..", "docs", "测试资产", "测试样例", "rag-spike");
    private static final Path OUTPUT_DIR = Path.of("target", "rag-eval");

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;

    @Autowired
    RagModeComparisonDatabaseEvalTest(KbDocumentRepository documents, KbChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {KbDocument.class, KbChunk.class})
    @EnableJpaRepositories(basePackageClasses = {KbDocumentRepository.class, KbChunkRepository.class})
    static class TestConfig {
    }

    @Test
    void comparesBaselineAdapterAndAugmentorRecallAgainstSameFixture() throws Exception {
        RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);
        List<RagSpikeDocumentFixture> fixtureDocuments = loader.loadDocuments();
        Map<Long, Long> savedIdByFixtureId = seedDocuments(loader, fixtureDocuments);
        List<RagSpikeEvalCase> cases = loader.loadCases().stream()
                .map(evalCase -> remapCaseIds(evalCase, savedIdByFixtureId))
                .toList();

        SimpleKnowledgeSearchService searchService = new SimpleKnowledgeSearchService(chunks, documents);
        BaselineRagEvaluationResult baseline = new BaselineRagRunner(
                "baseline-search-service",
                new KnowledgeSearchServiceBaselineBackend(searchService)
        ).run(cases);

        LangChain4jAdapterRagRunner adapterRunner = new LangChain4jAdapterRagRunner(
                evalCase -> retriever(searchService, evalCase)
        );
        BaselineRagEvaluationResult adapter = adapterRunner.run(cases);

        LangChain4jAugmentorRagRunner augmentorRunner = new LangChain4jAugmentorRagRunner(
                evalCase -> retriever(searchService, evalCase),
                new HeuristicCompressingChatModel(),
                true
        );
        BaselineRagEvaluationResult augmentor = augmentorRunner.run(cases);

        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "rag-spike-database-mode-comparison",
                List.of(baseline, adapter, augmentor)
        );
        Path jsonPath = OUTPUT_DIR.resolve("rag-mode-comparison-database.json");
        Path markdownPath = OUTPUT_DIR.resolve("rag-mode-comparison-database.md");
        RagModeComparisonReportWriter.writeJson(comparison, jsonPath);
        RagModeComparisonReportWriter.writeMarkdown(comparison, markdownPath);

        assertThat(comparison.summary().totalModes()).isEqualTo(3);
        assertThat(comparison.summary().totalCases()).isEqualTo(10);
        assertThat(comparison.modeResults())
                .allSatisfy(result -> assertThat(result.summary().forbiddenHitCount()).isZero());
        assertThat(comparison.caseComparisons()).hasSize(10);
        assertThat(Files.exists(jsonPath)).isTrue();
        assertThat(Files.exists(markdownPath)).isTrue();
    }

    private ContentRetriever retriever(SimpleKnowledgeSearchService searchService, RagSpikeEvalCase evalCase) {
        return new KnowledgeSearchServiceContentRetriever(searchService, evalCase.userId(), evalCase.topK());
    }

    private Map<Long, Long> seedDocuments(RagSpikeFixtureLoader loader,
                                          List<RagSpikeDocumentFixture> fixtureDocuments) throws Exception {
        Map<Long, Long> savedIdByFixtureId = new LinkedHashMap<>();
        for (RagSpikeDocumentFixture fixture : fixtureDocuments) {
            KbDocument document = new KbDocument(
                    fixture.userId() == null ? 0L : fixture.userId(),
                    fixture.filename(),
                    "READY",
                    "PUBLIC".equalsIgnoreCase(fixture.visibility())
            );
            document.setSourceType(fixture.sourceType());
            document.setProjectId(fixture.projectId());
            document.setLineageId(fixture.lineageId());
            document.setVersionStatus(fixture.versionStatus());
            document.setVersionNo("SUPERSEDED".equalsIgnoreCase(fixture.versionStatus()) ? 1 : 2);
            document.setCanonicalKey(fixture.citationId());
            KbDocument saved = documents.saveAndFlush(document);
            chunks.saveAndFlush(new KbChunk(saved.getId(), 0, loader.readDocumentText(fixture)));
            savedIdByFixtureId.put(fixture.documentId(), saved.getId());
        }
        return savedIdByFixtureId;
    }

    private RagSpikeEvalCase remapCaseIds(RagSpikeEvalCase evalCase, Map<Long, Long> savedIdByFixtureId) {
        return new RagSpikeEvalCase(
                evalCase.caseId(),
                evalCase.area(),
                evalCase.previousContext(),
                evalCase.query(),
                evalCase.userId(),
                evalCase.projectId(),
                evalCase.topK(),
                remapIds(evalCase.expectedDocumentIds(), savedIdByFixtureId),
                remapIds(evalCase.forbiddenDocumentIds(), savedIdByFixtureId),
                evalCase.expectedCitationIds(),
                evalCase.expectedAnswerFacts(),
                remapRankingRules(evalCase.rankingRules(), savedIdByFixtureId),
                evalCase.notes()
        );
    }

    private List<Long> remapIds(List<Long> ids, Map<Long, Long> savedIdByFixtureId) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .map(savedIdByFixtureId::get)
                .filter(id -> id != null)
                .toList();
    }

    private List<RagSpikeEvalCase.RankingRule> remapRankingRules(List<RagSpikeEvalCase.RankingRule> rules,
                                                                 Map<Long, Long> savedIdByFixtureId) {
        if (rules == null) {
            return List.of();
        }
        return rules.stream()
                .map(rule -> new RagSpikeEvalCase.RankingRule(
                        savedIdByFixtureId.get(rule.preferredDocumentId()),
                        savedIdByFixtureId.get(rule.lowerPriorityDocumentId()),
                        rule.reason()
                ))
                .filter(rule -> rule.preferredDocumentId() != null && rule.lowerPriorityDocumentId() != null)
                .toList();
    }
}
