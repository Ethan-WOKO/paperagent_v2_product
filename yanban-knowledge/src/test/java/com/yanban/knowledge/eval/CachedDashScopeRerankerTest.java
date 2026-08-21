package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.yanban.knowledge.service.KnowledgeSearchResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CachedDashScopeRerankerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reranksOnlySelectedCandidatesAndReusesCachedResponse() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/reranks");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.test/reranks"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().json("""
                        {"model":"qwen3-rerank","query":"alpha","documents":["one","two"],
                         "top_n":2,"return_documents":false}
                        """))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":1,"relevance_score":0.91},
                          {"index":0,"relevance_score":0.32}],
                         "usage":{"total_tokens":17}}
                        """, MediaType.APPLICATION_JSON));

        CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                builder.build(), "test-key", "qwen3-rerank",
                temporaryDirectory.resolve("rerank.jsonl"));
        List<KnowledgeSearchResult> candidates = List.of(
                candidate(1L, "one"), candidate(2L, "two"), candidate(3L, "three"));

        List<KnowledgeSearchResult> first = reranker.rerank("alpha", candidates, 2);
        List<KnowledgeSearchResult> second = reranker.rerank("alpha", candidates, 2);

        assertThat(first).extracting(KnowledgeSearchResult::documentId).containsExactly(2L, 1L, 3L);
        assertThat(second).extracting(KnowledgeSearchResult::documentId).containsExactly(2L, 1L, 3L);
        assertThat(first.get(0).rerankReason()).isEqualTo("qwen3-rerank:api_default");
        assertThat(first.get(2).rerankScore()).isNull();
        assertThat(reranker.telemetry().logicalCalls()).isEqualTo(2);
        assertThat(reranker.telemetry().apiCalls()).isEqualTo(1);
        assertThat(reranker.telemetry().cacheHits()).isEqualTo(1);
        assertThat(reranker.telemetry().logicalTokens()).isEqualTo(34);
        assertThat(reranker.telemetry().billedTokensThisRun()).isEqualTo(17);
        assertThat(reranker.telemetry().retryAttempts()).isZero();

        CachedDashScopeReranker reloaded = new CachedDashScopeReranker(
                builder.build(), "test-key", "qwen3-rerank",
                temporaryDirectory.resolve("rerank.jsonl"));
        assertThat(reloaded.rerank("alpha", candidates, 2))
                .extracting(KnowledgeSearchResult::documentId).containsExactly(2L, 1L, 3L);
        assertThat(reloaded.telemetry().apiCalls()).isZero();
        assertThat(reloaded.telemetry().cacheHits()).isEqualTo(1);
        server.verify();
    }

    @Test
    void sendsTaskInstructionAndKeepsItsCacheSeparate() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/reranks");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.test/reranks"))
                .andExpect(jsonPath("$.instruct").value(RetrievalIntent.CLAIM_EVIDENCE.instruction()))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.88}],
                         "usage":{"total_tokens":9}}
                        """, MediaType.APPLICATION_JSON));
        CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                builder.build(), "test-key", "qwen3-rerank",
                temporaryDirectory.resolve("intent-rerank.jsonl"));

        List<KnowledgeSearchResult> ranked = reranker.rerank(
                "claim", List.of(candidate(1L, "evidence")), 1, RetrievalIntent.CLAIM_EVIDENCE);

        assertThat(ranked.get(0).rerankReason()).isEqualTo("qwen3-rerank:claim_evidence");
        assertThat(reranker.telemetry().billedTokensThisRun()).isEqualTo(9);
        server.verify();
    }

    private KnowledgeSearchResult candidate(long id, String text) {
        return new KnowledgeSearchResult(id, "doc-" + id, 0, text, 0.1d, true);
    }
}
