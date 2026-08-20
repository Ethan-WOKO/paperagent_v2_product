package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.ElasticsearchKnowledgeIndexService;
import com.yanban.knowledge.service.ElasticsearchKnowledgeIndexProvisioner;
import com.yanban.knowledge.service.ElasticsearchKnowledgeSearchIndexClient;
import com.yanban.knowledge.service.HybridKnowledgeSearchService;
import com.yanban.knowledge.service.IndexedChunkDocument;
import com.yanban.knowledge.service.KnowledgeSearchOptions;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.SimpleKnowledgeSearchService;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = RealElasticsearchHybridRagE2eTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RealElasticsearchHybridRagE2eTest {

    private static final Path FIXTURE_ROOT = Path.of("..", "docs", "测试资产", "测试样例", "rag-spike");
    private static final String DEFAULT_ENDPOINT = "http://localhost:9200";
    private static final int VECTOR_DIMENSIONS = 4;

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;

    @Autowired
    RealElasticsearchHybridRagE2eTest(KbDocumentRepository documents, KbChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {KbDocument.class, KbChunk.class})
    @EnableJpaRepositories(basePackageClasses = {KbDocumentRepository.class, KbChunkRepository.class})
    static class TestConfig {
    }

    @Test
    void runsFixtureCasesAgainstRealElasticsearchHybridSearch() throws Exception {
        assumeTrue(Boolean.getBoolean("yanban.real-es-e2e"),
                "Set -Dyanban.real-es-e2e=true to run against a local Elasticsearch instance.");

        String endpoint = System.getProperty("yanban.real-es-endpoint", DEFAULT_ENDPOINT);
        String indexName = "yanban-kb-chunks-e2e-" + System.currentTimeMillis();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setEndpoint(endpoint);
        properties.setIndexName(indexName);
        properties.setVectorDimensions(VECTOR_DIMENSIONS);
        properties.setMigrateLegacyIndex(false);

        try (RestClient restClient = RestClient.builder(HttpHost.create(endpoint)).build();
             ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper())) {
            ElasticsearchKnowledgeIndexProvisioner provisioner = new ElasticsearchKnowledgeIndexProvisioner(
                    restClient, objectMapper, properties);
            provisioner.ensureReady();

            ElasticsearchClient elasticsearchClient = new ElasticsearchClient(transport);
            ElasticsearchKnowledgeIndexService indexService = new ElasticsearchKnowledgeIndexService(
                    elasticsearchClient,
                    restClient,
                    properties,
                    objectMapper,
                    provisioner
            );
            ElasticsearchKnowledgeSearchIndexClient indexClient = new ElasticsearchKnowledgeSearchIndexClient(
                    restClient, objectMapper, properties, provisioner);
            HybridKnowledgeSearchService searchService = new HybridKnowledgeSearchService(
                    this::vectorFor, indexClient,
                    documents,
                    new SimpleKnowledgeSearchService(chunks, documents)
            );

            RagSpikeFixtureLoader loader = new RagSpikeFixtureLoader(FIXTURE_ROOT);
            SeedResult seed = seedDocumentsAndIndex(loader, loader.loadDocuments(), indexService);
            seed = seed.withArchivedDocumentId(seedArchivedDocument(indexService));
            SeedResult finalSeed = seed;
            refreshIndex(restClient, indexName);

            List<RagSpikeEvalCase> evaluationCases = loader.loadCases().stream()
                    .map(evalCase -> remapCase(evalCase, finalSeed))
                    .toList();
            List<BaselineRagEvaluationResult> modeResults = Arrays.stream(RetrievalBaselineMode.values())
                    .filter(mode -> !mode.requiresModelReranker())
                    .map(mode -> new BaselineRagRunner(
                            "real-es-" + mode.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                            new ElasticsearchRetrievalBaselineBackend(
                                    mode, indexClient, this::vectorFor, documents))
                            .run(evaluationCases))
                    .toList();
            RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                    "real-es-retrieval-strategy-baseline", modeResults);
            RagModeComparisonReportWriter.writeJson(
                    comparison, Path.of("target", "rag-eval", "real-es-retrieval-baseline.json"));
            RagModeComparisonReportWriter.writeMarkdown(
                    comparison, Path.of("target", "rag-eval", "real-es-retrieval-baseline.md"));

            BaselineRagRunner runner = new BaselineRagRunner(
                    "real-elasticsearch-hybrid-rag-e2e",
                    new KnowledgeSearchServiceBaselineBackend(searchService)
            );
            BaselineRagEvaluationResult result = runner.run(evaluationCases);
            runner.writeJson(result, Path.of("target", "rag-eval", "real-es-hybrid-rag-e2e.json"));

            assertThat(comparison.summary().totalModes()).isEqualTo(4);
            assertThat(comparison.summary().totalCases()).isEqualTo(10);
            assertThat(comparison.modeResults())
                    .allSatisfy(mode -> {
                        assertThat(mode.summary().rankingEligibleCases()).isEqualTo(7);
                        assertThat(mode.summary().forbiddenHitCount()).isZero();
                        assertThat(mode.summary().recallAtN()).containsKeys(1, 3, 5, 10, 20, 50);
                        assertThat(mode.summary().ndcgAtN()).containsKeys(1, 3, 5, 10, 20, 50);
                    });
            assertThat(result.summary().totalCases()).isEqualTo(10);
            assertThat(result.summary().forbiddenHitCount()).isZero();
            assertThat(result.summary().passedCases()).isGreaterThanOrEqualTo(8);
            assertThat(findCase(result, "RAG-LC4J-001").retrievedDocumentIds())
                    .contains(seed.savedIdByFixtureId().get(1002L));
            assertThat(findCase(result, "RAG-LC4J-002").retrievedDocumentIds())
                    .doesNotContain(
                            seed.savedIdByFixtureId().get(1001L),
                            seed.savedIdByFixtureId().get(1002L),
                            seed.savedIdByFixtureId().get(1003L),
                            seed.savedIdByFixtureId().get(1004L),
                            seed.savedIdByFixtureId().get(1005L)
                    );
            assertThat(findCase(result, "RAG-LC4J-005").retrievedDocumentIds())
                    .doesNotContain(seed.savedIdByFixtureId().get(1005L));

            List<KnowledgeSearchResult> historicalResults = searchService.search(
                    "Recall@5",
                    new KnowledgeSearchOptions(101L, 10, null, true)
            );
            assertThat(historicalResults)
                    .extracting(KnowledgeSearchResult::documentId)
                    .contains(seed.savedIdByFixtureId().get(1001L), seed.savedIdByFixtureId().get(1004L))
                    .doesNotContain(seed.savedIdByFixtureId().get(1005L), seed.archivedDocumentId());

            assertThat(searchService.search(
                    "archived-only-calibration-token",
                    new KnowledgeSearchOptions(101L, 10, null, true)
            )).extracting(KnowledgeSearchResult::documentId)
                    .doesNotContain(seed.archivedDocumentId());
        } finally {
            deleteIndexQuietly(endpoint, indexName);
        }
    }

    @Test
    void provisionsDenseVectorIndexAndMigratesLegacyFloatArray() throws Exception {
        assumeTrue(Boolean.getBoolean("yanban.real-es-e2e"),
                "Set -Dyanban.real-es-e2e=true to run against a local Elasticsearch instance.");

        String endpoint = System.getProperty("yanban.real-es-endpoint", DEFAULT_ENDPOINT);
        long suffix = System.currentTimeMillis();
        String legacyIndex = "yanban-kb-legacy-e2e-" + suffix;
        String targetIndex = "yanban-kb-v2-e2e-" + suffix;
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        try (RestClient restClient = RestClient.builder(HttpHost.create(endpoint)).build()) {
            Request legacyDocument = new Request("PUT", "/" + legacyIndex + "/_doc/legacy-1?refresh=true");
            legacyDocument.setJsonEntity("""
                    {
                      "chunkId": 1,
                      "documentId": 1,
                      "userId": 101,
                      "isPublic": false,
                      "sourceType": "USER_UPLOAD",
                      "versionStatus": "ACTIVE",
                      "versionNo": 1,
                      "chunkIndex": 0,
                      "text": "legacy migration probe",
                      "vector": [0.9, 0.1, 0.0, 0.0]
                    }
                    """);
            EntityUtils.consumeQuietly(restClient.performRequest(legacyDocument).getEntity());

            KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
            properties.setIndexName(targetIndex);
            properties.setLegacyIndexName(legacyIndex);
            properties.setVectorDimensions(VECTOR_DIMENSIONS);
            properties.setMigrateLegacyIndex(true);
            new ElasticsearchKnowledgeIndexProvisioner(restClient, objectMapper, properties).ensureReady();

            JsonNode count = objectMapper.readTree(EntityUtils.toString(restClient.performRequest(
                    new Request("GET", "/" + targetIndex + "/_count")).getEntity()));
            assertThat(count.path("count").asInt()).isEqualTo(1);
            JsonNode mapping = objectMapper.readTree(EntityUtils.toString(restClient.performRequest(
                    new Request("GET", "/" + targetIndex + "/_mapping")).getEntity()));
            assertThat(mapping.path(targetIndex).path("mappings").path("properties")
                    .path("vector").path("type").asText()).isEqualTo("dense_vector");
        } finally {
            deleteIndexQuietly(endpoint, targetIndex);
            deleteIndexQuietly(endpoint, legacyIndex);
        }
    }

    private SeedResult seedDocumentsAndIndex(RagSpikeFixtureLoader loader,
                                             List<RagSpikeDocumentFixture> fixtureDocuments,
                                             ElasticsearchKnowledgeIndexService indexService) throws Exception {
        Map<Long, Long> savedIdByFixtureId = new LinkedHashMap<>();
        Map<Long, String> citationIdByFixtureId = new LinkedHashMap<>();
        for (RagSpikeDocumentFixture fixture : fixtureDocuments) {
            KbDocument document = new KbDocument(
                    fixture.userId() == null ? 0L : fixture.userId(),
                    fixture.filename(),
                    "READY",
                    "PUBLIC".equalsIgnoreCase(fixture.visibility())
            );
            document.setSourceType(fixture.sourceType());
            document.setProjectId(fixture.projectId());
            document.setLineageId(fixture.lineageId());
            document.setVersionStatus(fixture.versionStatus());
            document.setVersionNo("SUPERSEDED".equalsIgnoreCase(fixture.versionStatus()) ? 1 : 2);
            document.setCanonicalKey(fixture.citationId());
            KbDocument savedDocument = documents.saveAndFlush(document);
            KbChunk savedChunk = chunks.saveAndFlush(new KbChunk(savedDocument.getId(), 0, loader.readDocumentText(fixture)));
            indexChunk(indexService, savedDocument, savedChunk);
            savedIdByFixtureId.put(fixture.documentId(), savedDocument.getId());
            citationIdByFixtureId.put(fixture.documentId(), fixture.filename() + "#chunk-0");
        }
        return new SeedResult(savedIdByFixtureId, citationIdByFixtureId, null);
    }

    private Long seedArchivedDocument(ElasticsearchKnowledgeIndexService indexService) {
        KbDocument archived = new KbDocument(101L, "archived-note.md", "READY", false);
        archived.setSourceType("LAB_NOTE");
        archived.setVersionStatus("ARCHIVED");
        archived.setLineageId("archived-calibration");
        archived.setCanonicalKey("archived-note-001");
        KbDocument savedDocument = documents.saveAndFlush(archived);
        KbChunk savedChunk = chunks.saveAndFlush(new KbChunk(
                savedDocument.getId(),
                0,
                "archived-only-calibration-token"
        ));
        indexChunk(indexService, savedDocument, savedChunk);
        return savedDocument.getId();
    }

    private void indexChunk(ElasticsearchKnowledgeIndexService indexService, KbDocument document, KbChunk chunk) {
        indexService.indexChunk(new IndexedChunkDocument(
                chunk.getId(),
                document.getId(),
                document.getUserId(),
                document.getProjectId(),
                Boolean.TRUE.equals(document.getIsPublic()),
                document.getSourceType(),
                document.getVersionStatus(),
                document.getLineageId(),
                document.getVersionNo(),
                document.getCanonicalKey(),
                chunk.getChunkIndex(),
                chunk.getChunkText(),
                vectorFor(chunk.getChunkText())
        ));
    }

    private RagSpikeEvalCase remapCase(RagSpikeEvalCase evalCase, SeedResult seed) {
        List<Long> expectedDocumentIds = remapIds(evalCase.expectedDocumentIds(), seed.savedIdByFixtureId());
        return new RagSpikeEvalCase(
                evalCase.caseId(),
                evalCase.area(),
                evalCase.previousContext(),
                evalCase.query(),
                evalCase.userId(),
                evalCase.projectId(),
                evalCase.topK(),
                expectedDocumentIds,
                remapIds(evalCase.forbiddenDocumentIds(), seed.savedIdByFixtureId()),
                expectedDocumentIds.stream()
                        .map(seed.citationIdBySavedDocumentId()::get)
                        .filter(value -> value != null && !value.isBlank())
                        .toList(),
                evalCase.expectedAnswerFacts(),
                remapRankingRules(evalCase.rankingRules(), seed.savedIdByFixtureId()),
                evalCase.notes()
        );
    }

    private List<Long> remapIds(List<Long> ids, Map<Long, Long> savedIdByFixtureId) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .map(savedIdByFixtureId::get)
                .filter(id -> id != null)
                .toList();
    }

    private List<RagSpikeEvalCase.RankingRule> remapRankingRules(List<RagSpikeEvalCase.RankingRule> rules,
                                                                 Map<Long, Long> savedIdByFixtureId) {
        if (rules == null) {
            return List.of();
        }
        return rules.stream()
                .map(rule -> new RagSpikeEvalCase.RankingRule(
                        savedIdByFixtureId.get(rule.preferredDocumentId()),
                        savedIdByFixtureId.get(rule.lowerPriorityDocumentId()),
                        rule.reason()
                ))
                .filter(rule -> rule.preferredDocumentId() != null && rule.lowerPriorityDocumentId() != null)
                .toList();
    }

    private List<Double> vectorFor(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return List.of(
                lower.contains("delta-graph-rag") || lower.contains("graph") ? 0.95d : 0.05d,
                lower.contains("public") || lower.contains("faithfulness") ? 0.90d : 0.05d,
                lower.contains("deadline") || lower.contains("2026-09-18") ? 0.85d : 0.05d,
                lower.contains("recall@5") || lower.contains("citation coverage") ? 0.90d : 0.05d
        );
    }

    private void refreshIndex(RestClient restClient, String indexName) throws Exception {
        EntityUtils.consumeQuietly(restClient.performRequest(new Request("POST", "/" + indexName + "/_refresh")).getEntity());
    }

    private void deleteIndexQuietly(String endpoint, String indexName) {
        try (RestClient restClient = RestClient.builder(HttpHost.create(endpoint)).build()) {
            EntityUtils.consumeQuietly(restClient.performRequest(new Request("DELETE", "/" + indexName)).getEntity());
        } catch (Exception ignored) {
        }
    }

    private BaselineRagEvaluationResult.CaseResult findCase(BaselineRagEvaluationResult result, String caseId) {
        return result.cases().stream()
                .filter(item -> caseId.equals(item.caseId()))
                .findFirst()
                .orElseThrow();
    }

    private record SeedResult(
            Map<Long, Long> savedIdByFixtureId,
            Map<Long, String> citationIdBySavedDocumentId,
            Long archivedDocumentId
    ) {
        SeedResult withArchivedDocumentId(Long archivedDocumentId) {
            return new SeedResult(savedIdByFixtureId, citationIdBySavedDocumentId, archivedDocumentId);
        }

        private SeedResult {
            Map<Long, String> fixtureCitationIds = citationIdBySavedDocumentId;
            Map<Long, String> savedCitationIds = new LinkedHashMap<>();
            for (Map.Entry<Long, Long> entry : savedIdByFixtureId.entrySet()) {
                savedCitationIds.put(entry.getValue(), fixtureCitationIds.get(entry.getKey()));
            }
            citationIdBySavedDocumentId = savedCitationIds;
        }
    }
}
