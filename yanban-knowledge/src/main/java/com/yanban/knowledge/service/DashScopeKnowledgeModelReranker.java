package com.yanban.knowledge.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.knowledge.config.KnowledgeRerankProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public final class DashScopeKnowledgeModelReranker implements KnowledgeModelReranker {

    private final RestClient restClient;
    private final KnowledgeRerankProperties properties;

    public DashScopeKnowledgeModelReranker(RestClient restClient, KnowledgeRerankProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.isEnabled()
                && properties.getApiKey() != null
                && !properties.getApiKey().isBlank();
    }

    @Override
    public List<KnowledgeSearchResult> rerank(
            String query,
            List<KnowledgeSearchResult> candidates,
            int topK) {
        if (!available() || candidates.isEmpty() || topK <= 0) {
            return candidates.stream().limit(Math.max(0, topK)).toList();
        }
        int selectedCount = Math.min(properties.getCandidateLimit(), candidates.size());
        List<KnowledgeSearchResult> selected = List.copyOf(candidates.subList(0, selectedCount));
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                JsonNode response = restClient.post()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new RerankRequest(
                                properties.getModel(),
                                query,
                                selected.stream().map(KnowledgeSearchResult::chunkText).toList(),
                                selectedCount,
                                false))
                        .retrieve()
                        .body(JsonNode.class);
                return merge(candidates, selected, response, topK);
            } catch (RuntimeException ex) {
                failure = ex;
                if (attempt < properties.getMaxAttempts()) waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("DashScope knowledge rerank failed after bounded retries", failure);
    }

    private List<KnowledgeSearchResult> merge(
            List<KnowledgeSearchResult> candidates,
            List<KnowledgeSearchResult> selected,
            JsonNode response,
            int topK) {
        if (response == null || !response.path("results").isArray()) {
            throw new IllegalStateException("DashScope knowledge rerank response is malformed");
        }
        List<RankedIndex> rankedIndices = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : response.path("results")) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= selected.size() || !seen.add(index)
                    || !item.has("relevance_score")) {
                throw new IllegalStateException("DashScope knowledge rerank returned invalid indices");
            }
            rankedIndices.add(new RankedIndex(index, item.path("relevance_score").asDouble()));
        }
        if (rankedIndices.size() != selected.size()) {
            throw new IllegalStateException("DashScope knowledge rerank response size mismatch");
        }
        rankedIndices.sort(Comparator.comparingDouble(RankedIndex::score).reversed());
        List<KnowledgeSearchResult> reranked = new ArrayList<>(candidates.size());
        for (RankedIndex item : rankedIndices) {
            reranked.add(selected.get(item.index()).withRerank(
                    item.score(), properties.getModel() + ":api_default"));
        }
        if (selected.size() < candidates.size()) {
            reranked.addAll(candidates.subList(selected.size(), candidates.size()));
        }
        return reranked.stream().limit(topK).toList();
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(properties.getRetryBackoff().toMillis() * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope knowledge rerank retry interrupted", interrupted);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            int top_n,
            boolean return_documents) {
    }

    private record RankedIndex(int index, double score) {
    }
}
