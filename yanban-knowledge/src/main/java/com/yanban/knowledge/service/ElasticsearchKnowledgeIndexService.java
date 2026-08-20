package com.yanban.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchKnowledgeIndexService implements KnowledgeIndexService {

    private final ElasticsearchClient elasticsearchClient;
    private final RestClient restClient;
    private final KnowledgeElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final ElasticsearchKnowledgeIndexProvisioner indexProvisioner;

    public ElasticsearchKnowledgeIndexService(ElasticsearchClient elasticsearchClient,
                                              RestClient restClient,
                                              KnowledgeElasticsearchProperties properties,
                                              ObjectMapper objectMapper,
                                              ElasticsearchKnowledgeIndexProvisioner indexProvisioner) {
        this.elasticsearchClient = elasticsearchClient;
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.indexProvisioner = indexProvisioner;
    }

    @Override
    public String indexChunk(IndexedChunkDocument chunkDocument) {
        try {
            indexProvisioner.ensureReady();
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("chunkId", chunkDocument.chunkId());
            document.put("documentId", chunkDocument.documentId());
            document.put("userId", chunkDocument.userId());
            document.put("projectId", chunkDocument.projectId());
            document.put("isPublic", chunkDocument.isPublic());
            document.put("sourceType", chunkDocument.sourceType());
            document.put("versionStatus", chunkDocument.versionStatus());
            document.put("lineageId", chunkDocument.lineageId());
            document.put("versionNo", chunkDocument.versionNo());
            document.put("canonicalKey", chunkDocument.canonicalKey());
            document.put("chunkIndex", chunkDocument.chunkIndex());
            document.put("text", chunkDocument.text());
            document.put("vector", chunkDocument.vector());
            IndexResponse response = elasticsearchClient.index(request -> request
                    .index(properties.getIndexName())
                    .document(document));
            return response.id();
        } catch (IOException ex) {
            throw new IllegalStateException("写入 Elasticsearch 失败", ex);
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        try {
            indexProvisioner.ensureReady();
            Request request = new Request("POST", "/" + properties.getIndexName() + "/_delete_by_query");
            request.setJsonEntity(objectMapper.writeValueAsString(Map.of(
                    "query", Map.of(
                            "term", Map.of("documentId", documentId)
                    )
            )));
            EntityUtils.consumeQuietly(restClient.performRequest(request).getEntity());
        } catch (IOException ex) {
            throw new IllegalStateException("删除 Elasticsearch 文档失败", ex);
        }
    }
}
