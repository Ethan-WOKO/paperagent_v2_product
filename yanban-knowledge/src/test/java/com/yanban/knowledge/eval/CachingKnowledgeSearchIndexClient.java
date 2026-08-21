package com.yanban.knowledge.eval;

import com.yanban.knowledge.service.KnowledgeSearchIndexClient;
import com.yanban.knowledge.service.KnowledgeSearchIndexHit;
import com.yanban.knowledge.service.KnowledgeSearchOptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-process route cache so an RRF parameter grid does not repeat Elasticsearch retrieval. */
final class CachingKnowledgeSearchIndexClient implements KnowledgeSearchIndexClient {

    private final KnowledgeSearchIndexClient delegate;
    private final Map<LexicalKey, List<KnowledgeSearchIndexHit>> lexical = new LinkedHashMap<>();
    private final Map<VectorKey, List<KnowledgeSearchIndexHit>> vector = new LinkedHashMap<>();
    private long lexicalMisses;
    private long vectorMisses;

    CachingKnowledgeSearchIndexClient(KnowledgeSearchIndexClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized List<KnowledgeSearchIndexHit> searchLexical(
            String query, KnowledgeSearchOptions options, int topK) {
        LexicalKey key = new LexicalKey(query, options, topK);
        return lexical.computeIfAbsent(key, ignored -> {
            lexicalMisses++;
            return List.copyOf(delegate.searchLexical(query, options, topK));
        });
    }

    @Override
    public synchronized List<KnowledgeSearchIndexHit> searchVector(
            List<Double> queryVector, KnowledgeSearchOptions options, int topK) {
        VectorKey key = new VectorKey(List.copyOf(queryVector), options, topK);
        return vector.computeIfAbsent(key, ignored -> {
            vectorMisses++;
            return List.copyOf(delegate.searchVector(queryVector, options, topK));
        });
    }

    Telemetry telemetry() {
        return new Telemetry(lexical.size(), vector.size(), lexicalMisses, vectorMisses);
    }

    private record LexicalKey(String query, KnowledgeSearchOptions options, int topK) {
    }

    private record VectorKey(List<Double> queryVector, KnowledgeSearchOptions options, int topK) {
    }

    record Telemetry(long lexicalEntries, long vectorEntries, long lexicalMisses, long vectorMisses) {
    }
}
