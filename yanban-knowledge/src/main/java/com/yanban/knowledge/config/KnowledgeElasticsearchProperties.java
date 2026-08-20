package com.yanban.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.knowledge.elasticsearch")
public class KnowledgeElasticsearchProperties {

    private String endpoint;
    private String indexName = "yanban-kb-chunks-v2";
    private Integer vectorDimensions = 1024;
    private String legacyIndexName = "yanban-kb-chunks-v1";
    private boolean migrateLegacyIndex = true;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public Integer getVectorDimensions() {
        return vectorDimensions;
    }

    public void setVectorDimensions(Integer vectorDimensions) {
        this.vectorDimensions = vectorDimensions;
    }

    public String getLegacyIndexName() {
        return legacyIndexName;
    }

    public void setLegacyIndexName(String legacyIndexName) {
        this.legacyIndexName = legacyIndexName;
    }

    public boolean isMigrateLegacyIndex() {
        return migrateLegacyIndex;
    }

    public void setMigrateLegacyIndex(boolean migrateLegacyIndex) {
        this.migrateLegacyIndex = migrateLegacyIndex;
    }
}
