package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanban.knowledge.config.KnowledgeUploadProperties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class KnowledgeResourceLimiterTest {
    @Test
    void uploadWaitsForPerUserPermitInsteadOfRejectingRequest() throws Exception {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        properties.setMaxConcurrentUploads(2);
        properties.setMaxConcurrentUploadsPerUser(1);
        KnowledgeResourceLimiter limiter = new KnowledgeResourceLimiter(properties);
        var executor = Executors.newSingleThreadExecutor();

        try (KnowledgeResourceLimiter.Permit first = limiter.upload(1L)) {
            var waiting = executor.submit(() -> {
                try (KnowledgeResourceLimiter.Permit ignored = limiter.upload(1L)) {
                    return true;
                }
            });
            assertThatThrownBy(() -> waiting.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.close();
            assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void uploadWaitsForGlobalPermitAcrossUsersInsteadOfRejectingRequest() throws Exception {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        properties.setMaxConcurrentUploads(1);
        properties.setMaxConcurrentUploadsPerUser(1);
        KnowledgeResourceLimiter limiter = new KnowledgeResourceLimiter(properties);
        var executor = Executors.newSingleThreadExecutor();

        try (KnowledgeResourceLimiter.Permit first = limiter.upload(1L)) {
            var waiting = executor.submit(() -> {
                try (KnowledgeResourceLimiter.Permit ignored = limiter.upload(2L)) {
                    return true;
                }
            });
            assertThatThrownBy(() -> waiting.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.close();
            assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releasedUploadPermitCanBeAcquiredAgain() {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        KnowledgeResourceLimiter limiter = new KnowledgeResourceLimiter(properties);

        try (KnowledgeResourceLimiter.Permit ignored = limiter.upload(1L)) {
            // Permit is held for the request scope.
        }
        try (KnowledgeResourceLimiter.Permit ignored = limiter.upload(1L)) {
            assertThat(ignored).isNotNull();
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
