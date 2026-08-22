package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanban.knowledge.config.KnowledgeUploadProperties;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class KnowledgeResourceLimiterTest {
    @Test
    void enforcesGlobalAndPerUserCapacityAndReleasesPermit() {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        properties.setMaxConcurrentUploads(2);
        properties.setMaxConcurrentUploadsPerUser(1);
        KnowledgeResourceLimiter limiter = new KnowledgeResourceLimiter(properties);

        try (KnowledgeResourceLimiter.Permit ignored = limiter.upload(1L)) {
            assertThatThrownBy(() -> limiter.upload(1L))
                    .isInstanceOf(KnowledgeCapacityException.class)
                    .hasMessageContaining("user capacity");
            try (KnowledgeResourceLimiter.Permit otherUser = limiter.upload(2L)) {
                assertThatThrownBy(() -> limiter.upload(3L))
                        .isInstanceOf(KnowledgeCapacityException.class)
                        .hasMessageContaining("global capacity");
            }
        }

        try (KnowledgeResourceLimiter.Permit ignored = limiter.upload(1L)) {
            // Released permits can be acquired again.
        }
    }

    @Test
    void embeddingWaitsForPerUserPermitInsteadOfFailingTask() throws Exception {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        properties.setMaxConcurrentEmbeddingBatches(2);
        properties.setMaxConcurrentEmbeddingBatchesPerUser(1);
        KnowledgeResourceLimiter limiter = new KnowledgeResourceLimiter(properties);
        var executor = Executors.newSingleThreadExecutor();

        try (KnowledgeResourceLimiter.Permit first = limiter.embedding(1L)) {
            var waiting = executor.submit(() -> {
                try (KnowledgeResourceLimiter.Permit ignored = limiter.embedding(1L)) {
                    return true;
                }
            });
            assertThatThrownBy(() -> waiting.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.close();
            org.assertj.core.api.Assertions.assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
