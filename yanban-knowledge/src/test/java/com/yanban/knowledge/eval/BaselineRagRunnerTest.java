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
        assertThat(result.summary().forbiddenHitCount()).isZero();
        assertThat(result.cases())
                .extracting(BaselineRagEvaluationResult.CaseResult::caseId)
                .contains("RAG-LC4J-001", "RAG-LC4J-010");
        assertThat(Files.readString(output)).contains("\"runner\" : \"current-rag-baseline\"");
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
}
