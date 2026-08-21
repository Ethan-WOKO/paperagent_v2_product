package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeSearchIndexClient {
    List<KnowledgeSearchIndexHit> searchLexical(String query, KnowledgeSearchOptions options, int topK);

    List<KnowledgeSearchIndexHit> searchVector(List<Double> queryVector, KnowledgeSearchOptions options, int topK);
}
