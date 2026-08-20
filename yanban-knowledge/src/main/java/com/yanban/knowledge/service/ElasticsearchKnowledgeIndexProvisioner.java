package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ElasticsearchKnowledgeIndexProvisioner {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeElasticsearchProperties properties;
    private volatile boolean ready;

    public ElasticsearchKnowledgeIndexProvisioner(RestClient restClient,
                                                   ObjectMapper objectMapper,
                                                   KnowledgeElasticsearchProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void ensureReady() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            boolean created = false;
            try {
                if (!indexExists(properties.getIndexName())) {
                    createIndex();
                    created = true;
                    migrateLegacyIndexIfPresent();
                }
                validateMapping();
                ready = true;
            } catch (Exception ex) {
                if (created) {
                    deleteTargetQuietly();
                }
                throw new IllegalStateException("Elasticsearch 知识库索引初始化失败", ex);
            }
        }
    }

    String buildIndexJson() throws IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunkId", Map.of("type", "long"));
        fields.put("documentId", Map.of("type", "long"));
        fields.put("userId", Map.of("type", "long"));
        fields.put("projectId", Map.of("type", "long"));
        fields.put("isPublic", Map.of("type", "boolean"));
        fields.put("sourceType", Map.of("type", "keyword"));
        fields.put("versionStatus", Map.of("type", "keyword"));
        fields.put("lineageId", Map.of("type", "keyword"));
        fields.put("versionNo", Map.of("type", "integer"));
        fields.put("canonicalKey", Map.of("type", "keyword"));
        fields.put("chunkIndex", Map.of("type", "integer"));
        fields.put("text", Map.of("type", "text"));
        fields.put("vector", Map.of(
                "type", "dense_vector",
                "dims", properties.getVectorDimensions(),
                "index", true,
                "similarity", "cosine"
        ));
        return objectMapper.writeValueAsString(Map.of("mappings", Map.of("properties", fields)));
    }

    String buildReindexJson() throws IOException {
        return objectMapper.writeValueAsString(Map.of(
                "source", Map.of("index", properties.getLegacyIndexName()),
                "dest", Map.of("index", properties.getIndexName())
        ));
    }

    private boolean indexExists(String indexName) throws IOException {
        if (!StringUtils.hasText(indexName)) {
            return false;
        }
        try {
            Response response = restClient.performRequest(new Request("HEAD", "/" + indexName));
            EntityUtils.consumeQuietly(response.getEntity());
            int status = response.getStatusLine().getStatusCode();
            if (status == 404) {
                return false;
            }
            if (status >= 200 && status < 300) {
                return true;
            }
            throw new IllegalStateException("Elasticsearch 索引存在性检查返回 HTTP " + status);
        } catch (ResponseException ex) {
            if (ex.getResponse().getStatusLine().getStatusCode() == 404) {
                return false;
            }
            throw ex;
        }
    }

    private void createIndex() throws IOException {
        Request request = new Request("PUT", "/" + properties.getIndexName());
        request.setJsonEntity(buildIndexJson());
        EntityUtils.consumeQuietly(restClient.performRequest(request).getEntity());
    }

    private void migrateLegacyIndexIfPresent() throws IOException {
        if (!properties.isMigrateLegacyIndex()
                || !StringUtils.hasText(properties.getLegacyIndexName())
                || properties.getIndexName().equals(properties.getLegacyIndexName())
                || !indexExists(properties.getLegacyIndexName())) {
            return;
        }
        Request request = new Request("POST", "/_reindex?wait_for_completion=true&refresh=true");
        request.setJsonEntity(buildReindexJson());
        Response response = restClient.performRequest(request);
        JsonNode result = objectMapper.readTree(EntityUtils.toString(response.getEntity()));
        if (result.path("timed_out").asBoolean(false) || !result.path("failures").isEmpty()) {
            throw new IllegalStateException("Elasticsearch 旧索引迁移未完整成功");
        }
    }

    private void validateMapping() throws IOException {
        Request request = new Request("GET", "/" + properties.getIndexName() + "/_mapping");
        Response response = restClient.performRequest(request);
        JsonNode root = objectMapper.readTree(EntityUtils.toString(response.getEntity()));
        JsonNode vector = root.path(properties.getIndexName())
                .path("mappings").path("properties").path("vector");
        if (!"dense_vector".equals(vector.path("type").asText())
                || vector.path("dims").asInt(-1) != properties.getVectorDimensions()
                || !vector.path("index").asBoolean(false)) {
            throw new IllegalStateException("Elasticsearch vector mapping 与 KNN 配置不兼容");
        }
    }

    private void deleteTargetQuietly() {
        try {
            EntityUtils.consumeQuietly(restClient.performRequest(
                    new Request("DELETE", "/" + properties.getIndexName())).getEntity());
        } catch (Exception ignored) {
        }
    }
}
