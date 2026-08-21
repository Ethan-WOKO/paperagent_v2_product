package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.KnowledgeSearchOptions;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.SimpleKnowledgeSearchService;
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
@ContextConfiguration(classes = BaselineRagDatabaseEvalTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class BaselineRagDatabaseEvalTest {

    private static final Path FIXTURE_ROOT = Path.of("..", "docs", "测试资产", "测试样例", "rag-spike");

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;

    @Autowired
    BaselineRagDatabaseEvalTest(KbDocumentRepository documents, KbChunkRepository chunks) {
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
    void runsFixtureCasesAgainstDatabaseBackedBaseline() throws Exception {
        RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);
        List<RagSpikeDocumentFixture> fixtureDocuments = loader.loadDocuments();
        Map<Long, Long> savedIdByFixtureId = seedDocuments(loader, fixtureDocuments);
        SimpleKnowledgeSearchService searchService = new SimpleKnowledgeSearchService(chunks, documents);
        BaselineRagRunner runner = new BaselineRagRunner(
                "current-rag-database-baseline",
                new KnowledgeSearchServiceBaselineBackend(searchService)
        );

        BaselineRagEvaluationResult result = runner.run(loader.loadCases().stream()
                .map(evalCase -> remapCaseIds(evalCase, savedIdByFixtureId))
                .toList());

        assertThat(result.summary().totalCases()).isEqualTo(10);
        assertThat(result.summary().forbiddenHitCount()).isZero();
        assertThat(findCase(result, "RAG-LC4J-002").retrievedDocumentIds())
                .doesNotContain(savedIdByFixtureId.get(1002L));
        assertThat(savedIdByFixtureId).containsKey(1005L);
        assertThat(findCase(result, "RAG-LC4J-005").retrievedDocumentIds())
                .doesNotContain(savedIdByFixtureId.get(1005L));
    }

    @Test
    void databaseBaselineKeepsUserPrivateDocumentsIsolated() throws Exception {
        RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);
        Map<Long, Long> savedIdByFixtureId = seedDocuments(loader, loader.loadDocuments());
        SimpleKnowledgeSearchService searchService = new SimpleKnowledgeSearchService(chunks, documents);

        List<KnowledgeSearchResult> userBResults = searchService.search("Delta-Graph-RAG", 202L, 10);
        List<KnowledgeSearchResult> userAResults = searchService.search("Recall@5 0.78", 101L, 10);
        List<KnowledgeSearchResult> publicResults = searchService.search("public guidance", 202L, 10);

        assertThat(userBResults)
                .extracting(KnowledgeSearchResult::documentId)
                .doesNotContain(savedIdByFixtureId.get(1002L), savedIdByFixtureId.get(1003L));
        assertThat(userAResults)
                .extracting(KnowledgeSearchResult::documentId)
                .contains(savedIdByFixtureId.get(1002L));
        assertThat(publicResults)
                .extracting(KnowledgeSearchResult::documentId)
                .contains(savedIdByFixtureId.get(3001L));
    }

    @Test
    void databaseBaselineAppliesVersionFilteringAndSourceTypePriority() throws Exception {
        RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);
        Map<Long, Long> savedIdByFixtureId = seedDocuments(loader, loader.loadDocuments());
        SimpleKnowledgeSearchService searchService = new SimpleKnowledgeSearchService(chunks, documents);

        List<KnowledgeSearchResult> defaultResults = searchService.search("Recall@5", 101L, 10);
        assertThat(defaultResults)
                .extracting(KnowledgeSearchResult::documentId)
                .contains(savedIdByFixtureId.get(1002L))
                .doesNotContain(savedIdByFixtureId.get(1001L), savedIdByFixtureId.get(1004L), savedIdByFixtureId.get(1005L));

        List<KnowledgeSearchResult> historicalResults = searchService.search(
                "Recall@5",
                new KnowledgeSearchOptions(101L, 10, null, true)
        );
        assertThat(historicalResults)
                .extracting(KnowledgeSearchResult::documentId)
                .contains(savedIdByFixtureId.get(1001L), savedIdByFixtureId.get(1002L), savedIdByFixtureId.get(1004L))
                .doesNotContain(savedIdByFixtureId.get(1005L));
        assertThat(indexOf(historicalResults, savedIdByFixtureId.get(1002L)))
                .isLessThan(indexOf(historicalResults, savedIdByFixtureId.get(1001L)));
        assertThat(historicalResults.stream()
                .filter(result -> savedIdByFixtureId.get(1002L).equals(result.documentId()))
                .findFirst()
                .orElseThrow()
                .sourceType()).isEqualTo("PAPER_POLISHED");
    }

    @Test
    void databaseBaselineNeverInjectsArchivedDocuments() {
        KbDocument archived = new KbDocument(101L, "archived-note.md", "READY", false);
        archived.setSourceType("LAB_NOTE");
        archived.setVersionStatus("ARCHIVED");
        KbDocument saved = documents.saveAndFlush(archived);
        chunks.saveAndFlush(new KbChunk(saved.getId(), 0, "archived-only-calibration-token"));

        SimpleKnowledgeSearchService searchService = new SimpleKnowledgeSearchService(chunks, documents);

        assertThat(searchService.search("archived-only-calibration-token", 101L, 10)).isEmpty();
        assertThat(searchService.search(
                "archived-only-calibration-token",
                new KnowledgeSearchOptions(101L, 10, null, true)
        )).isEmpty();
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

    private BaselineRagEvaluationResult.CaseResult findCase(BaselineRagEvaluationResult result, String caseId) {
        return result.cases().stream()
                .filter(item -> caseId.equals(item.caseId()))
                .findFirst()
                .orElseThrow();
    }

    private int indexOf(List<KnowledgeSearchResult> results, Long documentId) {
        for (int i = 0; i < results.size(); i++) {
            if (documentId.equals(results.get(i).documentId())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
