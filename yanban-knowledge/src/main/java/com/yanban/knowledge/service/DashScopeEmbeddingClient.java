package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.knowledge.config.KnowledgeEmbeddingProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

public class DashScopeEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final KnowledgeEmbeddingProperties properties;

    public DashScopeEmbeddingClient(KnowledgeEmbeddingProperties properties) {
        this(RestClient.builder().baseUrl(properties.getApiUrl()).build(), properties);
    }

    public DashScopeEmbeddingClient(RestClient restClient, KnowledgeEmbeddingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public List<Double> embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("DASHSCOPE_API_KEY 未配置");
        }
        if (texts == null || texts.isEmpty()) return List.of();
        JsonNode response = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingRequest(properties.getModel(), texts))
                .retrieve()
                .body(JsonNode.class);

        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("DashScope embedding 响应格式非法");
        }
        List<List<Double>> vectors = new ArrayList<>();
        data.forEach(value -> {
            JsonNode embeddingNode = value.path("embedding");
            if (!embeddingNode.isArray()) throw new IllegalStateException("DashScope embedding 响应格式非法");
            List<Double> vector = new ArrayList<>();
            embeddingNode.forEach(item -> vector.add(item.asDouble()));
            vectors.add(List.copyOf(vector));
        });
        return List.copyOf(vectors);
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }
}
