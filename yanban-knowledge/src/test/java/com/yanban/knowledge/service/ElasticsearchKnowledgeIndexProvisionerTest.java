package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import org.junit.jupiter.api.Test;

class ElasticsearchKnowledgeIndexProvisionerTest {

    @Test
    void buildsIndexedDenseVectorMappingAndLegacyReindexRequest() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setIndexName("knowledge-v2");
        properties.setLegacyIndexName("knowledge-v1");
        properties.setVectorDimensions(4);
        ElasticsearchKnowledgeIndexProvisioner provisioner = new ElasticsearchKnowledgeIndexProvisioner(
                null, objectMapper, properties);

        JsonNode mapping = objectMapper.readTree(provisioner.buildIndexJson())
                .path("mappings").path("properties");
        assertThat(mapping.path("text").path("type").asText()).isEqualTo("text");
        assertThat(mapping.path("versionStatus").path("type").asText()).isEqualTo("keyword");
        assertThat(mapping.path("vector").path("type").asText()).isEqualTo("dense_vector");
        assertThat(mapping.path("vector").path("dims").asInt()).isEqualTo(4);
        assertThat(mapping.path("vector").path("index").asBoolean()).isTrue();

        JsonNode reindex = objectMapper.readTree(provisioner.buildReindexJson());
        assertThat(reindex.path("source").path("index").asText()).isEqualTo("knowledge-v1");
        assertThat(reindex.path("dest").path("index").asText()).isEqualTo("knowledge-v2");
    }
}
