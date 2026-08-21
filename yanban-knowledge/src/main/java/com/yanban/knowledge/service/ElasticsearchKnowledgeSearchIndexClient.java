package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchKnowledgeSearchIndexClient implements KnowledgeSearchIndexClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeElasticsearchProperties properties;
    private final ElasticsearchKnowledgeIndexProvisioner indexProvisioner;

    public ElasticsearchKnowledgeSearchIndexClient(RestClient restClient,
                                                    ObjectMapper objectMapper,
                                                    KnowledgeElasticsearchProperties properties,
                                                    ElasticsearchKnowledgeIndexProvisioner indexProvisioner) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.indexProvisioner = indexProvisioner;
    }

    @Override
    public List<KnowledgeSearchIndexHit> searchLexical(String query, KnowledgeSearchOptions options, int topK) {
        return execute(buildLexicalQueryJson(query, options, topK));
    }

    @Override
    public List<KnowledgeSearchIndexHit> searchVector(List<Double> queryVector,
                                                      KnowledgeSearchOptions options,
                                                      int topK) {
        return execute(buildVectorQueryJson(queryVector, options, topK));
    }

    private List<KnowledgeSearchIndexHit> execute(String requestBody) {
        try {
            indexProvisioner.ensureReady();
            Request request = new Request("POST", "/" + properties.getIndexName() + "/_search");
            request.setJsonEntity(requestBody);
            Response response = restClient.performRequest(request);
            return parseResponse(EntityUtils.toString(response.getEntity()));
        } catch (IOException ex) {
            throw new IllegalStateException("执行 Elasticsearch 检索失败", ex);
        }
    }

    String buildLexicalQueryJson(String query, KnowledgeSearchOptions options, int topK) {
        try {
            String escapedQuery = objectMapper.writeValueAsString(query);
            return """
                    {
                      "size": %d,
                      "query": {
                        "bool": {
                          "filter": %s,
                          "must": [
                            { "match": { "text": { "query": %s } } }
                          ]
                        }
                      }
                    }
                    """.formatted(topK, filterArrayJson(options), escapedQuery);
        } catch (IOException ex) {
            throw new IllegalStateException("构造 Elasticsearch BM25 查询失败", ex);
        }
    }

    String buildVectorQueryJson(List<Double> queryVector, KnowledgeSearchOptions options, int topK) {
        try {
            String vectorJson = objectMapper.writeValueAsString(queryVector);
            int numCandidates = Math.max(topK, Math.min(1000, topK * 4));
            return """
                    {
                      "size": %d,
                      "knn": {
                        "field": "vector",
                        "query_vector": %s,
                        "k": %d,
                        "num_candidates": %d,
                        "filter": {
                          "bool": {
                            "filter": %s
                          }
                        }
                      }
                    }
                    """.formatted(topK, vectorJson, topK, numCandidates, filterArrayJson(options));
        } catch (IOException ex) {
            throw new IllegalStateException("构造 Elasticsearch KNN 查询失败", ex);
        }
    }

    private String filterArrayJson(KnowledgeSearchOptions options) {
        String versionStatuses = options.includeSuperseded()
                ? "[\"ACTIVE\", \"SUPERSEDED\"]"
                : "[\"ACTIVE\"]";
        String projectFilter = options.projectId() == null
                ? ""
                : """
                            ,
                            {
                              "bool": {
                                "should": [
                                  { "bool": { "must_not": { "exists": { "field": "projectId" } } } },
                                  { "term": { "projectId": %d } }
                                ],
                                "minimum_should_match": 1
                              }
                            }
                  """.formatted(options.projectId());
        return """
                [
                  {
                    "bool": {
                      "should": [
                        { "term": { "userId": %d } },
                        { "term": { "isPublic": true } }
                      ],
                      "minimum_should_match": 1
                    }
                  },
                  { "terms": { "versionStatus": %s } }
                  %s
                ]
                """.formatted(options.userId(), versionStatuses, projectFilter).trim();
    }

    private List<KnowledgeSearchIndexHit> parseResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        List<KnowledgeSearchIndexHit> hits = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new KnowledgeSearchIndexHit(
                    source.path("documentId").asLong(),
                    source.path("chunkIndex").asInt(),
                    source.path("text").asText(),
                    hit.path("_score").asDouble(0.0)
            ));
        }
        return hits;
    }
}
