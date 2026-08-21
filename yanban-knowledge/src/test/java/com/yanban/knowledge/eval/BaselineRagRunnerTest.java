package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineRagRunnerTest {

    private static final Path FIXTURE_ROOT = Path.of("..", "docs", "测试资产", "测试样例", "rag-spike");

    @Test
    void loadsRagSpikeFixturesAndCases() throws Exception {
        RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);

        assertThat(loader.loadDocuments()).hasSize(7);
        assertThat(loader.loadCases()).hasSize(10);
    }

    @Test
    void evaluatesFixtureBackedBaselineAndWritesJson(@TempDir Path tempDir) throws Exception {
        RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);
        List<RagSpikeDocumentFixture> documents = loader.loadDocuments();
        BaselineRagRunner runner = new BaselineRagRunner(new FixtureBackedBaselineSearchBackend(loader, documents));

        BaselineRagEvaluationResult result = runner.run(loader.loadCases());
        Path output = tempDir.resolve("baseline-rag-result.json");
        runner.writeJson(result, output);

        assertThat(result.summary().totalCases()).isEqualTo(10);
        assertThat(result.summary().rankingEligibleCases()).isEqualTo(7);
        assertThat(result.summary().recallAtN()).containsKeys(1, 3, 5, 10);
        assertThat(result.summary().ndcgAtN()).containsKeys(1, 3, 5, 10);
        assertThat(result.summary().forbiddenHitCount()).isZero();
        assertThat(result.cases())
                .extracting(BaselineRagEvaluationResult.CaseResult::caseId)
                .contains("RAG-LC4J-001", "RAG-LC4J-010");
        assertThat(Files.readString(output)).contains("\"runner\" : \"current-rag-baseline\"");
    }

    @Test
    void calculatesRankingMetricsOnlyFromCasesWithRelevantDocuments() {
        RagSpikeEvalCase ranked = evalCase("ranked", List.of(1L, 2L));
        RagSpikeEvalCase noAnswer = evalCase("no-answer", List.of());
        BaselineRagRunner runner = new BaselineRagRunner("metric-probe", evalCase ->
                "ranked".equals(evalCase.caseId())
                        ? List.of(hit(3L), hit(1L), hit(4L), hit(2L))
                        : List.of(hit(9L)));

        BaselineRagEvaluationResult result = runner.run(List.of(ranked, noAnswer));

        assertThat(result.summary().rankingEligibleCases()).isEqualTo(1);
        assertThat(result.summary().recallAtN().get(1)).isZero();
        assertThat(result.summary().recallAtN().get(3)).isEqualTo(0.5d);
        assertThat(result.summary().recallAtN().get(5)).isEqualTo(1.0d);
        assertThat(result.summary().meanReciprocalRankAt10()).isEqualTo(0.5d);
        assertThat(result.summary().ndcgAtN().get(10)).isBetween(0.0d, 1.0d);
        assertThat(result.summary().latencyP50Millis()).isGreaterThanOrEqualTo(0.0d);
    }

    @Test
    void duplicateChunksFromOneRelevantDocumentDoNotInflateNdcg() {
        RagSpikeEvalCase ranked = evalCase("duplicate-chunks", List.of(1L));
        BaselineRagRunner runner = new BaselineRagRunner("duplicate-probe", ignored ->
                List.of(hit(1L), hit(1L), hit(1L)));

        BaselineRagEvaluationResult result = runner.run(List.of(ranked));

        assertThat(result.summary().ndcgAtN().get(3)).isEqualTo(1.0d);
        assertThat(result.summary().ndcgAtN().values()).allMatch(value -> value <= 1.0d);
    }

    @Test
    void knowledgeSearchServiceBackendMapsSearchResults() {
        KnowledgeSearchServiceBaselineBackend backend = new KnowledgeSearchServiceBaselineBackend((query, userId, topK) -> List.of(
                new com.yanban.knowledge.service.KnowledgeSearchResult(
                        1002L,
                        "active-paper-polished.md",
                        0,
                        "Recall@5 0.78",
                        2.0d,
                        false
                )
        ));
        RagSpikeEvalCase evalCase = new RagSpikeEvalCase(
                "RAG-LC4J-001",
                "private_active_recall",
                null,
                "Recall@5?",
                101L,
                null,
                5,
                List.of(1002L),
                List.of(),
                List.of("active-paper-polished.md#chunk-0"),
                List.of("Recall@5 0.78"),
                List.of(),
                null
        );

        List<BaselineRagHit> hits = backend.search(evalCase);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).documentId()).isEqualTo(1002L);
        assertThat(hits.get(0).citationId()).isEqualTo("active-paper-polished.md#chunk-0");
    }

    private RagSpikeEvalCase evalCase(String id, List<Long> expected) {
        return new RagSpikeEvalCase(
                id, "metric", null, "query", 1L, null, 10,
                expected, List.of(), List.of(), List.of(), List.of(), null);
    }

    private BaselineRagHit hit(Long documentId) {
        return new BaselineRagHit(
                documentId, "doc-" + documentId + ".md", 0, "text", 1.0d,
                "doc-" + documentId + "#chunk-0", "knowledge_base", "ACTIVE", "PRIVATE");
    }
}
