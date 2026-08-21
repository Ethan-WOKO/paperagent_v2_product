package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.KnowledgeSearchIndexClient;
import com.yanban.knowledge.service.KnowledgeSearchIndexHit;
import com.yanban.knowledge.service.KnowledgeSearchOptions;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElasticsearchRetrievalBaselineBackendTest {

    @Test
    void weightedRrfChangesRouteInfluenceWithoutChangingCandidateBudget() {
        KnowledgeSearchIndexClient index = new KnowledgeSearchIndexClient() {
            @Override
            public List<KnowledgeSearchIndexHit> searchLexical(
                    String query, KnowledgeSearchOptions options, int topK) {
                return List.of(hit(1L, 10.0d), hit(2L, 9.0d));
            }

            @Override
            public List<KnowledgeSearchIndexHit> searchVector(
                    List<Double> queryVector, KnowledgeSearchOptions options, int topK) {
                return List.of(hit(2L, 1.0d), hit(3L, 0.9d));
            }
        };
        EmbeddingClient embeddings = ignored -> List.of(1.0d);
        ElasticsearchRetrievalBaselineBackend backend = new ElasticsearchRetrievalBaselineBackend(
                RetrievalBaselineMode.RRF,
                index,
                embeddings,
                this::document,
                null,
                new RrfConfiguration(0.1d, 1.0d, 60),
                RetrievalIntent.GENERAL_RESEARCH);

        List<BaselineRagHit> hits = backend.search(new RagSpikeEvalCase(
                "weighted", "test", null, "query", 1L, null, 10,
                List.of(), List.of(), List.of(), List.of(), List.of(), "test"));

        assertThat(hits).extracting(BaselineRagHit::documentId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void rejectsInvalidRrfWeights() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RrfConfiguration(0.0d, 0.0d, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controlledRewriteAddsRoutesButKeepsTheFinalCandidateBudget() {
        KnowledgeSearchIndexClient index = new KnowledgeSearchIndexClient() {
            @Override
            public List<KnowledgeSearchIndexHit> searchLexical(
                    String query, KnowledgeSearchOptions options, int topK) {
                return query.equals("rewritten query")
                        ? List.of(hit(4L, 10.0d)) : List.of(hit(1L, 10.0d));
            }

            @Override
            public List<KnowledgeSearchIndexHit> searchVector(
                    List<Double> queryVector, KnowledgeSearchOptions options, int topK) {
                return queryVector.get(0) == 2.0d
                        ? List.of(hit(4L, 1.0d)) : List.of(hit(2L, 1.0d));
            }
        };
        EmbeddingClient embeddings = query -> List.of(query.equals("rewritten query") ? 2.0d : 1.0d);
        ElasticsearchRetrievalBaselineBackend backend = new ElasticsearchRetrievalBaselineBackend(
                RetrievalBaselineMode.RRF,
                index,
                embeddings,
                this::document,
                null,
                new RrfConfiguration(0.5d, 1.0d, 10),
                RetrievalIntent.API_DEFAULT,
                ignored -> List.of("rewritten query"),
                0.5d);

        List<BaselineRagHit> hits = backend.search(new RagSpikeEvalCase(
                "rewrite", "test", null, "original query", 1L, null, 10,
                List.of(), List.of(), List.of(), List.of(), List.of(), "test"));

        assertThat(hits).hasSizeLessThanOrEqualTo(50);
        assertThat(hits).extracting(BaselineRagHit::documentId).contains(1L, 2L, 4L);
    }

    private KnowledgeSearchIndexHit hit(long documentId, double score) {
        return new KnowledgeSearchIndexHit(documentId, 0, "doc-" + documentId, score);
    }

    private KnowledgeSearchResult document(Long documentId) {
        return new KnowledgeSearchResult(
                documentId, "doc-" + documentId, 0, "doc-" + documentId, 0.0d,
                true, "SCIFACT", "ACTIVE", null, 1, null, "doc:" + documentId);
    }
}
