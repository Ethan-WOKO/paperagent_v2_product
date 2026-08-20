package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElasticsearchKnowledgeSearchIndexClientTest {

    @Test
    void queryFiltersActiveVersionsByDefault() throws Exception {
        ElasticsearchKnowledgeSearchIndexClient client = client();

        String lexicalJson = client.buildLexicalQueryJson(
                "alpha",
                KnowledgeSearchOptions.activeOnly(1001L, 5),
                10
        );
        String vectorJson = client.buildVectorQueryJson(
                List.of(0.1d, 0.2d),
                KnowledgeSearchOptions.activeOnly(1001L, 5),
                10
        );

        assertThat(lexicalJson).contains("\"match\": { \"text\"");
        assertThat(lexicalJson).doesNotContain("\"knn\"");
        assertThat(vectorJson).contains("\"knn\"").contains("\"field\": \"vector\"");
        for (String json : List.of(lexicalJson, vectorJson)) {
            assertThat(json).contains("\"terms\": { \"versionStatus\": [\"ACTIVE\"] }");
            assertThat(json).contains("\"term\": { \"userId\": 1001 }");
            assertThat(json).doesNotContain("\"projectId\"");
        }
    }

    @Test
    void queryCanIncludeSupersededAndProjectFilter() throws Exception {
        ElasticsearchKnowledgeSearchIndexClient client = client();

        String lexicalJson = client.buildLexicalQueryJson(
                "alpha",
                new KnowledgeSearchOptions(1001L, 5, 42L, true),
                10
        );
        String vectorJson = client.buildVectorQueryJson(
                List.of(0.1d, 0.2d),
                new KnowledgeSearchOptions(1001L, 5, 42L, true),
                10
        );

        for (String json : List.of(lexicalJson, vectorJson)) {
            assertThat(json).contains("\"terms\": { \"versionStatus\": [\"ACTIVE\", \"SUPERSEDED\"] }");
            assertThat(json).contains("\"exists\": { \"field\": \"projectId\" }");
            assertThat(json).contains("\"term\": { \"projectId\": 42 }");
        }
    }

    private ElasticsearchKnowledgeSearchIndexClient client() {
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setIndexName("yanban-kb-chunks-v1");
        return new ElasticsearchKnowledgeSearchIndexClient(
                null,
                new ObjectMapper(),
                properties,
                org.mockito.Mockito.mock(ElasticsearchKnowledgeIndexProvisioner.class)
        );
    }
}
