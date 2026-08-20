package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.yanban.knowledge.config.KnowledgeRerankProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DashScopeKnowledgeModelRerankerTest {

    @Test
    void defaultsToTheEvaluatedTwentyCandidateWindow() {
        assertThat(new KnowledgeRerankProperties().getCandidateLimit()).isEqualTo(20);
    }

    @Test
    void reranksOnlyTheBoundedCandidateWindowAndReturnsRequestedTopK() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/rerank");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.test/rerank"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen3-rerank"))
                .andExpect(jsonPath("$.top_n").value(2))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.instruct").doesNotExist())
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":1,"relevance_score":0.95},
                          {"index":0,"relevance_score":0.25}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        KnowledgeRerankProperties properties = properties();
        properties.setCandidateLimit(2);
        DashScopeKnowledgeModelReranker reranker = new DashScopeKnowledgeModelReranker(
                builder.build(), properties);

        List<KnowledgeSearchResult> results = reranker.rerank(
                "research question", List.of(result(1L), result(2L), result(3L)), 2);

        assertThat(results).extracting(KnowledgeSearchResult::documentId).containsExactly(2L, 1L);
        assertThat(results.get(0).rerankScore()).isEqualTo(0.95d);
        assertThat(results.get(0).rerankReason()).isEqualTo("qwen3-rerank:api_default");
        server.verify();
    }

    @Test
    void rejectsIncompleteResponsesAfterBoundedRetries() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/rerank");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo("https://example.test/rerank"))
                    .andRespond(withSuccess("""
                            {"results":[{"index":0,"relevance_score":0.8}]}
                            """, MediaType.APPLICATION_JSON));
        }
        KnowledgeRerankProperties properties = properties();
        properties.setRetryBackoff(Duration.ZERO);
        DashScopeKnowledgeModelReranker reranker = new DashScopeKnowledgeModelReranker(
                builder.build(), properties);

        assertThatThrownBy(() -> reranker.rerank(
                "research question", List.of(result(1L), result(2L)), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bounded retries");
        server.verify();
    }

    @Test
    void missingApiKeyDisablesExternalRerank() {
        KnowledgeRerankProperties properties = properties();
        properties.setApiKey(" ");
        DashScopeKnowledgeModelReranker reranker = new DashScopeKnowledgeModelReranker(
                RestClient.create(), properties);

        assertThat(reranker.available()).isFalse();
        assertThat(reranker.rerank("query", List.of(result(1L), result(2L)), 1))
                .extracting(KnowledgeSearchResult::documentId)
                .containsExactly(1L);
    }

    private KnowledgeRerankProperties properties() {
        KnowledgeRerankProperties properties = new KnowledgeRerankProperties();
        properties.setApiKey("test-key");
        return properties;
    }

    private KnowledgeSearchResult result(long documentId) {
        return new KnowledgeSearchResult(
                documentId, "doc-" + documentId, 0, "content-" + documentId, 0.5d,
                true, "TEST", "ACTIVE", null, 1, null, "doc:" + documentId);
    }
}
