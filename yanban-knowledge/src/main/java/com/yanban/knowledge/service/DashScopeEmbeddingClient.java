package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.knowledge.config.KnowledgeEmbeddingProperties;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

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
        JsonNode response;
        try {
            response = restClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbeddingRequest(properties.getModel(), texts))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException failure) {
            throw new IllegalStateException(dependencyMessage(failure), failure);
        }

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

    private String dependencyMessage(ResourceAccessException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnknownHostException) {
                return "向量服务域名解析失败，请检查运行环境 DNS；系统将自动重试";
            }
            current = current.getCause();
        }
        return "向量服务暂时不可用，系统将自动重试";
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }
}
