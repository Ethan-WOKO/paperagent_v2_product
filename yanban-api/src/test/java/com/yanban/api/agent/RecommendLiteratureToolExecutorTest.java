package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.literature.LiteratureRecommendationService;
import com.yanban.paper.literature.LiteratureRecommendationService.RecommendationRequest;
import com.yanban.paper.literature.LiteratureRecommendationService.RecommendationResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecommendLiteratureToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeAcceptsStringBooleanArgumentsFromLlm() {
        LiteratureRecommendationService recommendationService = mock(LiteratureRecommendationService.class);
        when(recommendationService.recommendWithProgress(any(), any())).thenReturn(RecommendationResult.empty("ok"));
        RecommendLiteratureToolExecutor executor = new RecommendLiteratureToolExecutor(recommendationService, objectMapper);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "embodied intelligence");
        args.put("includeBibtex", "true");

        ToolResult result = executor.execute(new ToolCall("call-1", "recommend_literature", args));

        ArgumentCaptor<RecommendationRequest> captor = ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(recommendationService).recommendWithProgress(captor.capture(), any());
        assertThat(result.success()).isTrue();
        assertThat(captor.getValue().includeBibtex()).isTrue();
    }

    @Test
    void executeReturnsLiteratureBaseProtocolFields() {
        LiteratureRecommendationService recommendationService = mock(LiteratureRecommendationService.class);
        RecommendLiteratureToolExecutor executor = new RecommendLiteratureToolExecutor(recommendationService, objectMapper);
        when(recommendationService.recommendWithProgress(any(), any())).thenReturn(new RecommendationResult(
                "hybrid RAG",
                "topic search",
                List.of("hybrid RAG"),
                2,
                1,
                1,
                List.of(),
                List.of(),
                1,
                1,
                false,
                List.of(new LiteratureRecommendationService.RecommendationItem(
                        1L,
                        "Hybrid Retrieval for RAG",
                        List.of("A. Author"),
                        2024,
                        "Demo Journal",
                        "10.1000/rag",
                        null,
                        "W123",
                        "https://example.test/rag",
                        null,
                        42,
                        0.91,
                        "evidence",
                        "hybrid RAG",
                        true,
                        "Matches the request",
                        null,
                        "hybrid RAG",
                        List.of("score=0.910", "stable_identifier_or_url_available"),
                        "BIBTEX_NOT_REQUESTED_VERIFY_METADATA",
                        "LOW",
                        List.of(),
                        "doi:10.1000/rag",
                        "MERGED_DUPLICATES",
                        List.of("local_card", "openalex"),
                        1
                )),
                List.of()
        ));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "hybrid RAG");
        args.put("includeBibtex", false);

        ToolResult result = executor.execute(new ToolCall("call-1", "recommend_literature", args));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("items").get(0).path("matchTarget").asText()).isEqualTo("hybrid RAG");
        assertThat(result.output().path("items").get(0).path("citationStatus").asText()).isEqualTo("BIBTEX_NOT_REQUESTED_VERIFY_METADATA");
        assertThat(result.output().path("items").get(0).path("metadataRiskLevel").asText()).isEqualTo("LOW");
        assertThat(result.output().path("items").get(0).path("deduplicationKey").asText()).isEqualTo("doi:10.1000/rag");
        assertThat(result.output().path("items").get(0).path("duplicateStatus").asText()).isEqualTo("MERGED_DUPLICATES");
        assertThat(result.output().path("items").get(0).path("duplicateSources")).hasSize(2);
        assertThat(result.output().path("items").get(0).path("duplicateMergeCount").asInt()).isEqualTo(1);
        assertThat(executor.definition().description()).contains("Topic-based academic literature search v1");
        assertThat(executor.definition().description()).contains("Does not inspect full manuscripts");
    }
}
