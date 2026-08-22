package com.yanban.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KnowledgeUploadPropertiesTest {
    @Test
    void embeddingBatchDefaultsToDashScopeLimit() {
        assertThat(new KnowledgeUploadProperties().getEmbeddingBatchSize()).isEqualTo(10);
    }

    @Test
    void rejectsEmbeddingBatchAboveDashScopeLimit() {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();

        assertThatThrownBy(() -> properties.setEmbeddingBatchSize(11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 10");
    }
}
