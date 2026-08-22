package com.yanban.knowledge.service;

import io.micrometer.core.instrument.Metrics;
import java.time.Duration;

final class KnowledgeMetrics {
    private KnowledgeMetrics() {}
    static void upload(String outcome) { Metrics.counter("yanban.knowledge.uploads", "outcome", outcome).increment(); }
    static void processing(String outcome, Duration duration) {
        Metrics.counter("yanban.knowledge.processing", "outcome", outcome).increment();
        Metrics.timer("yanban.knowledge.processing.duration", "outcome", outcome).record(duration);
    }
    static void embeddingBatch(String outcome, int size, Duration duration) {
        Metrics.counter("yanban.knowledge.embedding.batches", "outcome", outcome).increment();
        Metrics.summary("yanban.knowledge.embedding.batch.size").record(size);
        Metrics.timer("yanban.knowledge.embedding.duration", "outcome", outcome).record(duration);
    }
    static void outbox(String outcome) { Metrics.counter("yanban.knowledge.outbox.dispatch", "outcome", outcome).increment(); }
    static void deadLetter(String action) { Metrics.counter("yanban.knowledge.dead.letters", "action", action).increment(); }
    static void reconciliation(String outcome) { Metrics.counter("yanban.knowledge.reconciliation", "outcome", outcome).increment(); }
}
